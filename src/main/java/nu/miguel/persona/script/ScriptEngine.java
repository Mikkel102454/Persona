package nu.miguel.persona.script;

import nu.miguel.persona.Main;
import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.content.Content.*;
import nu.miguel.persona.quest.ConditionEvaluator;
import nu.miguel.persona.quest.QuestService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;

/** Sequential asynchronous interpreter shared by dialogues, NPC hooks, and quest hooks. */
public final class ScriptEngine {
    public static final int MAX_DEPTH=32;
    public enum Kind { CONTINUE,STOP,JUMP,TRANSFER,DIALOGUE_END }
    public record Control(Kind kind,String dialogue,String node){
        public static Control next(){return new Control(Kind.CONTINUE,null,null);}public static Control stop(){return new Control(Kind.STOP,null,null);}
        public static Control jump(String node){return new Control(Kind.JUMP,null,node);}public static Control transfer(String dialogue,String node){return new Control(Kind.TRANSFER,dialogue,node);}public static Control end(){return new Control(Kind.DIALOGUE_END,null,null);}
    }
    public interface Host {
        default CompletionStage<Void> say(Say say,EffectExecutor.Context context){return CompletableFuture.completedFuture(null);}
        default CompletionStage<Integer> choose(List<ChoiceOption> options,EffectExecutor.Context context){return CompletableFuture.completedFuture(-1);}
        default void failure(String message,Throwable error,EffectExecutor.Context context){if(message!=null&&!message.isBlank())context.player().sendMessage(Component.text(message));}
    }
    private final Main plugin;private final ConditionEvaluator conditions;
    public ScriptEngine(Main plugin){this.plugin=plugin;conditions=new ConditionEvaluator(plugin);}
    public CompletionStage<Control> run(List<Step> script,EffectExecutor.Context context){return run(script,context,new Host(){},0);}
    public CompletionStage<Control> run(List<Step> script,EffectExecutor.Context context,Host host){return run(script,context,host,0);}
    private CompletionStage<Control> run(List<Step> script,EffectExecutor.Context context,Host host,int depth){if(depth>MAX_DEPTH){host.failure("Script nesting limit exceeded.",null,context);return CompletableFuture.completedFuture(Control.stop());}return chain(script,0,context,host,depth);}
    private CompletionStage<Control> chain(List<Step> script,int index,EffectExecutor.Context context,Host host,int depth){if(index>=script.size())return CompletableFuture.completedFuture(Control.next());return execute(script.get(index),context,host,depth).thenCompose(control->control.kind()==Kind.CONTINUE?chain(script,index+1,context,host,depth):CompletableFuture.completedFuture(control));}
    private CompletionStage<Control> execute(Step step,EffectExecutor.Context context,Host host,int depth){
        try{
            if(step instanceof Say s)return host.say(s,context).thenApply(x->Control.next());
            if(step instanceof If i){boolean yes=conditions.test(i.when(),context.player(),plugin.states().require(context.player()));return run(yes?i.thenScript():i.elseScript(),context,host,depth+1);}
            if(step instanceof ChoiceStep c){List<ChoiceOption> eligible=c.options().stream().filter(o->conditions.test(o.when(),context.player(),plugin.states().require(context.player()))).toList();if(eligible.isEmpty())return CompletableFuture.completedFuture(Control.next());return host.choose(eligible,context).thenCompose(index->index<0||index>=eligible.size()?CompletableFuture.completedFuture(Control.stop()):run(eligible.get(index).script(),context,host,depth+1));}
            if(step instanceof Goto g)return CompletableFuture.completedFuture(g.dialogue()==null?Control.jump(g.node()):Control.transfer(g.dialogue(),g.node()));
            if(step instanceof EndDialogue)return CompletableFuture.completedFuture(Control.end());if(step instanceof Stop)return CompletableFuture.completedFuture(Control.stop());
            if(step instanceof Wait w){CompletableFuture<Control> future=new CompletableFuture<>();long ticks=Math.max(1,(long)Math.ceil(w.duration().toMillis()/50.0));plugin.getServer().getScheduler().runTaskLater(plugin,()->future.complete(Control.next()),ticks);return future;}
            if(step instanceof RandomStep r){int total=r.options().stream().mapToInt(WeightedScript::weight).sum(),pick=ThreadLocalRandom.current().nextInt(total);WeightedScript selected=r.options().getLast();for(WeightedScript o:r.options()){pick-=o.weight();if(pick<0){selected=o;break;}}return run(selected.script(),context,host,depth+1);}
            if(step instanceof RunScript r){List<Step> target=plugin.registry().scripts().get(r.script());if(target==null){host.failure("Reusable script is unavailable: "+r.script(),null,context);return CompletableFuture.completedFuture(Control.stop());}return run(target,context,host,depth+1);}
            if(step instanceof Command c)return command(c,context,host,depth);
            return CompletableFuture.completedFuture(Control.next());
        }catch(Throwable e){host.failure("Script failed: "+message(e),e,context);return CompletableFuture.completedFuture(Control.stop());}
    }
    private CompletionStage<Control> command(Command c,EffectExecutor.Context context,Host host,int depth){String validation=validate(c,context);if(validation!=null)return outcome(c,false,validation,null,context,host,depth);CompletionStage<ExpansionTypes.CommandResult> stage;try{if(c.type().startsWith("persona:"))stage=CompletableFuture.completedFuture(builtin(c,context));else{var handler=plugin.api().handler(ExpansionTypes.Command.class,c.type()).orElseThrow();String error=handler.validate(plugin.api().context(context,c.type()),c.options());if(error!=null)return outcome(c,false,error,null,context,host,depth);stage=handler.execute(plugin.api().context(context,c.type()),c.options());}}catch(Throwable e){return outcome(c,false,"Command failed.",e,context,host,depth);}return onServer(stage).handle((result,error)->new Object[]{result,error}).thenCompose(pair->{Throwable error=(Throwable)pair[1];if(error!=null)return outcome(c,false,"Command failed.",error,context,host,depth);ExpansionTypes.CommandResult result=(ExpansionTypes.CommandResult)pair[0];if(result==null)result=ExpansionTypes.CommandResult.failure("Command returned no result.");return switch(result.kind()){case SUCCESS->outcome(c,true,null,null,context,host,depth);case FAILURE->outcome(c,false,result.message(),null,context,host,depth);case JUMP->CompletableFuture.completedFuture(Control.jump(result.node()));case TRANSFER->CompletableFuture.completedFuture(Control.transfer(result.dialogue(),result.node()));case DIALOGUE_END->CompletableFuture.completedFuture(Control.end());case STOP->CompletableFuture.completedFuture(Control.stop());};});}
    private CompletionStage<Control> outcome(Command c,boolean success,String message,Throwable error,EffectExecutor.Context context,Host host,int depth){List<Step> handler=success?c.onSuccess():c.onFailure();if(!handler.isEmpty())return run(handler,context,host,depth+1);if(success)return CompletableFuture.completedFuture(Control.next());host.failure(message==null?"Command failed.":message,error,context);return CompletableFuture.completedFuture(Control.stop());}
    private ExpansionTypes.CommandResult builtin(Command c,EffectExecutor.Context context){Player p=context.player();Map<String,Object> o=c.options();QuestService.Result result;switch(c.type()){
        case "persona:start-quest"->{result=plugin.quests().start(p,string(o,"quest"));return result.success()?ExpansionTypes.CommandResult.success():ExpansionTypes.CommandResult.failure(result.message());}
        case "persona:finish-quest"->{result=plugin.quests().finish(p,string(o,"quest"));return result.success()?ExpansionTypes.CommandResult.success():ExpansionTypes.CommandResult.failure(result.message());}
        case "persona:deliver-items"->{result=plugin.quests().deliver(p,string(o,"quest"),string(o,"objective"));return result.success()?ExpansionTypes.CommandResult.success():ExpansionTypes.CommandResult.failure(result.message());}
        default->{plugin.effects().executeCommand(c.type(),o,context);return ExpansionTypes.CommandResult.success();}
    }}
    private String validate(Command c,EffectExecutor.Context context){Map<String,Object> o=c.options();try{return switch(c.type()){case "persona:start-quest"->plugin.quests().canStart(context.player(),string(o,"quest")).success()?null:plugin.quests().canStart(context.player(),string(o,"quest")).message();case "persona:finish-quest"->plugin.quests().canFinish(context.player(),string(o,"quest")).success()?null:plugin.quests().canFinish(context.player(),string(o,"quest")).message();case "persona:deliver-items"->plugin.quests().canDeliver(context.player(),string(o,"quest"),string(o,"objective")).success()?null:plugin.quests().canDeliver(context.player(),string(o,"quest"),string(o,"objective")).message();case "persona:give-item","persona:take-item"->Material.matchMaterial(string(o,"material"))==null?"Unknown material "+string(o,"material"):integer(o,"amount",1)<1?"Amount must be positive.":null;default->null;};}catch(RuntimeException e){return e.getMessage();}}
    private static String string(Map<String,Object> m,String k){Object v=m.get(k);if(v==null||String.valueOf(v).isBlank())throw new IllegalArgumentException("Missing "+k+".");return String.valueOf(v);}private static int integer(Map<String,Object> m,String k,int d){Object v=m.get(k);return v instanceof Number n?n.intValue():v==null?d:Integer.parseInt(String.valueOf(v));}private static String message(Throwable e){Throwable x=e instanceof CompletionException&&e.getCause()!=null?e.getCause():e;return x.getMessage()==null?x.getClass().getSimpleName():x.getMessage();}
    private <T> CompletionStage<T> onServer(CompletionStage<T> source){CompletableFuture<T> result=new CompletableFuture<>();source.whenComplete((value,error)->{Runnable complete=()->{if(error==null)result.complete(value);else result.completeExceptionally(error);};if(plugin.getServer().isPrimaryThread())complete.run();else plugin.getServer().getScheduler().runTask(plugin,complete);});return result;}
}
