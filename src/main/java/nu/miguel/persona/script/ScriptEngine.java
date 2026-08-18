package nu.miguel.persona.script;

import nu.miguel.persona.Main;
import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.content.Content.*;
import nu.miguel.persona.content.Content;
import nu.miguel.persona.quest.ConditionEvaluator;
import nu.miguel.persona.quest.QuestService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import static nu.miguel.persona.script.ScriptDefinition.*;

/** Sequential asynchronous interpreter shared by dialogues, NPC hooks, and quest hooks. */
public final class ScriptEngine {
    private static final Set<String> PLAYER_TARGET_COMMANDS=Set.of("persona:start-quest","persona:finish-quest","persona:deliver-items",
            "persona:set-flag","persona:set-variable","persona:message","persona:action-bar","persona:title","persona:play-sound",
            "persona:particle","persona:give-item","persona:take-item","persona:give-experience","persona:run-command",
            "persona:teleport","persona:potion-effect","persona:npc-speak");
    public static final int MAX_DEPTH=32;
    public static final int MAX_LOOP_ITERATIONS=10_000;
    public static final int MAX_NODE_TRANSITIONS=100_000;
    public enum Kind { CONTINUE,STOP,JUMP,TRANSFER,DIALOGUE_END }
    public record Control(Kind kind,String dialogue,String node){
        public static Control next(){return new Control(Kind.CONTINUE,null,null);}public static Control stop(){return new Control(Kind.STOP,null,null);}
        public static Control jump(String node){return new Control(Kind.JUMP,null,node);}public static Control transfer(String dialogue,String node){return new Control(Kind.TRANSFER,dialogue,node);}public static Control end(){return new Control(Kind.DIALOGUE_END,null,null);}
    }
    public record ScriptResult(Control control,Map<String,Object> outputs){
        public ScriptResult { outputs=Map.copyOf(outputs); }
        public static ScriptResult failed(Control control){return new ScriptResult(control,Map.of());}
    }
    public interface Host {
        default CompletionStage<Void> say(Say say,EffectExecutor.Context context){return CompletableFuture.completedFuture(null);}
        default CompletionStage<Integer> choose(List<ChoiceOption> options,EffectExecutor.Context context){return CompletableFuture.completedFuture(-1);}
        default void failure(String message,Throwable error,EffectExecutor.Context context){if(message!=null&&!message.isBlank()&&context.player()!=null)context.player().sendMessage(Component.text(message));}
    }
    private record StateKey(String graph,String node,String npcInstance,UUID player) {}
    private record ActiveEvent(String graph,String npcInstance,UUID player) {}
    public record GraphTrace(long sequence,long at,String graph,String node,String status,
                             UUID player,String npcDefinition,String npcInstance,Map<String,String> values,String detail) {
        public GraphTrace { values=values==null?Map.of():Map.copyOf(values); }
    }
    private static final class ExecutionBudget { int transitions; }
    private final Main plugin;private final ConditionEvaluator conditions;
    private final Map<StateKey,Long> statefulNodes=new ConcurrentHashMap<>();
    private final Set<ActiveEvent> activeNpcEvents=ConcurrentHashMap.newKeySet();
    private final Deque<GraphTrace> graphTraces=new ArrayDeque<>();
    private long graphTraceSequence;
    public ScriptEngine(Main plugin){this.plugin=plugin;conditions=new ConditionEvaluator(plugin);}
    public void clearState(){statefulNodes.clear();activeNpcEvents.clear();synchronized(graphTraces){graphTraces.clear();}}
    public void clearStateForNpc(String npcInstance){statefulNodes.keySet().removeIf(key->Objects.equals(key.npcInstance(),npcInstance));activeNpcEvents.removeIf(key->Objects.equals(key.npcInstance(),npcInstance));}
    public List<GraphTrace> graphTraceHistory(){synchronized(graphTraces){return List.copyOf(graphTraces);}}
    private void graphTrace(String graph,String node,String status,EffectExecutor.Context context,
                            Map<String,?> values,String detail,String npcInstance){
        UUID player=context.player()==null?null:context.player().getUniqueId();
        Map<String,String> safe=new LinkedHashMap<>();
        if(values!=null)for(var entry:values.entrySet()){
            Object value=entry.getValue();String rendered;
            if(value==null)rendered="null";
            else if(value instanceof Player p)rendered=p.getUniqueId().toString();
            else if(value instanceof Boolean||value instanceof Number||value instanceof String||value instanceof Enum<?>)rendered=String.valueOf(value);
            else if(value instanceof Collection<?> collection)rendered="["+collection.size()+" values]";
            else rendered="<"+value.getClass().getSimpleName()+">";
            safe.put(entry.getKey(),rendered.length()>256?rendered.substring(0,256)+"…":rendered);
        }
        String npcDefinition=context.npc()==null?null:context.npc().id();
        synchronized(graphTraces){graphTraces.addLast(new GraphTrace(++graphTraceSequence,System.currentTimeMillis(),graph,node,status,
                player,npcDefinition,npcInstance,Map.copyOf(safe),detail));while(graphTraces.size()>1000)graphTraces.removeFirst();}
    }
    public CompletionStage<Control> run(List<Step> script,EffectExecutor.Context context){return run(script,context,new Host(){},0);}
    public CompletionStage<Control> run(List<Step> script,EffectExecutor.Context context,Host host){return run(script,context,host,0);}
    public CompletionStage<Control> run(ScriptDefinition graph,EffectExecutor.Context context){return runEvent(graph,eventValues(context),context);}
    public CompletionStage<Control> run(ScriptDefinition graph,EffectExecutor.Context context,Host host){return runEvent(graph,eventValues(context),context,host);}
    private CompletionStage<Control> run(List<Step> script,EffectExecutor.Context context,Host host,int depth){if(depth>MAX_DEPTH){host.failure("Script nesting limit exceeded.",null,context);return CompletableFuture.completedFuture(Control.stop());}return chain(script,0,context,host,depth);}
    private static Map<String,Object> eventValues(EffectExecutor.Context context){Map<String,Object> values=new LinkedHashMap<>();if(context.player()!=null)values.put("player",context.player());if(context.npc()!=null)values.put("npc",context.npc().id());if(context.citizensNpc()!=null){var trait=context.citizensNpc().getTraitNullable(nu.miguel.persona.citizens.PersonaTrait.class);values.put("npc-instance",trait==null||trait.instanceId()==null?context.citizensNpc().getUniqueId().toString():trait.instanceId());}if(context.dialogue()!=null)values.put("dialogue",context.dialogue().id());if(context.quest()!=null)values.put("quest",context.quest().id());if(context.phase()!=null)values.put("phase",context.phase().id());if(context.objective()!=null){values.put("objective",context.objective().id());values.put("progress",context.current());values.put("required",context.required());}return values;}
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
            if(step instanceof RunScript r){ScriptDefinition target=plugin.registry().scripts().get(r.script());if(target==null){host.failure("Reusable script is unavailable: "+r.script(),null,context);return CompletableFuture.completedFuture(Control.stop());}return run(target,r.inputs(),context,host).thenApply(ScriptResult::control);}
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

    /** Executes an explicit reusable graph and returns values only after its Output boundary is reached. */
    public CompletionStage<ScriptResult> run(ScriptDefinition script,Map<String,Object> inputs,EffectExecutor.Context context){
        return run(script,inputs,context,new Host(){},0,new LinkedHashSet<>(),new ExecutionBudget());
    }
    public CompletionStage<ScriptResult> run(ScriptDefinition script,Map<String,Object> inputs,EffectExecutor.Context context,Host host){
        return run(script,inputs,context,host,0,new LinkedHashSet<>(),new ExecutionBudget());
    }
    public CompletionStage<Control> runEvent(ScriptDefinition graph,Map<String,Object> eventValues,EffectExecutor.Context context){
        return runEvent(graph,eventValues,context,new Host(){});
    }
    public CompletionStage<Control> runEvent(ScriptDefinition graph,Map<String,Object> eventValues,EffectExecutor.Context context,Host host){
        if(graph==null)return CompletableFuture.completedFuture(Control.next());
        if(graph.boundary()!=ScriptDefinition.Boundary.EVENT)return CompletableFuture.failedFuture(new IllegalArgumentException("host graph must use $event"));
        return run(graph,eventValues,context,host,0,new LinkedHashSet<>(),new ExecutionBudget()).thenApply(ScriptResult::control);
    }
    public CompletionStage<Control> runNpcEvent(ScriptDefinition graph,Map<String,Object> eventValues,EffectExecutor.Context context){
        return runNpcEvent(graph,eventValues,context,new Host(){});
    }
    public CompletionStage<Control> runNpcEvent(ScriptDefinition graph,Map<String,Object> eventValues,
                                                EffectExecutor.Context context,Host host){
        if(graph==null)return CompletableFuture.completedFuture(Control.next());
        ActiveEvent key=new ActiveEvent(graph.id(),Objects.toString(eventValues.get("npc-instance"),""),context.player()==null?null:context.player().getUniqueId());
        if(!activeNpcEvents.add(key))return CompletableFuture.completedFuture(Control.stop());
        return runEvent(graph,eventValues,context,host).whenComplete((ignored,error)->activeNpcEvents.remove(key));
    }
    private CompletionStage<ScriptResult> run(ScriptDefinition script,Map<String,Object> supplied,EffectExecutor.Context context,Host host,int depth,LinkedHashSet<String> stack,ExecutionBudget budget){
        if(depth>MAX_DEPTH){host.failure("Script nesting limit exceeded.",null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
        if(!stack.add(script.id())){host.failure("Recursive reusable script call: "+String.join(" -> ",stack)+" -> "+script.id(),null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
        if(supplied==null){host.failure("Script inputs mapping is required.",null,context);stack.remove(script.id());return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
        Set<String> unknown=new LinkedHashSet<>(supplied.keySet());unknown.removeAll(script.inputs().keySet());
        if(!unknown.isEmpty()){host.failure("Unknown script input(s): "+String.join(", ",unknown),null,context);stack.remove(script.id());return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
        Map<String,Object> inputs=new LinkedHashMap<>();
        for(var parameter:script.inputs().entrySet()){
            Object value=supplied.get(parameter.getKey());if(value==null)value=parameter.getValue().defaultValue();
            if(value==null&&parameter.getValue().required()){host.failure("Missing required script input "+parameter.getKey()+".",null,context);stack.remove(script.id());return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
            if(value!=null&&!runtimeValue(parameter.getValue().type(),value)){host.failure("Script input "+parameter.getKey()+" requires exact type "+parameter.getValue().type().id()+".",null,context);stack.remove(script.id());return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
            if(value!=null)inputs.put(parameter.getKey(),value);
        }
        GraphFrame frame=new GraphFrame(script,inputs,context,host,depth,stack,budget);
        Connection first=frame.outgoing(new Endpoint(script.entryBoundary(),"exec"));
        if(first==null){stack.remove(script.id());return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
        return frame.follow(first.to()).handle((result,error)->{stack.remove(script.id());if(error==null)return result;host.failure("Script failed: "+message(error),error,context);return ScriptResult.failed(Control.stop());});
    }
    private static boolean runtimeValue(ScriptDefinition.ValueType type,Object value){if(type.list())return value instanceof List<?> values&&values.stream().allMatch(item->runtimeValue(type.elementType(),item));if(type.equals(ScriptDefinition.ValueType.BOOLEAN))return value instanceof Boolean;if(type.equals(ScriptDefinition.ValueType.INTEGER))return value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long;if(type.equals(ScriptDefinition.ValueType.NUMBER))return value instanceof Number;if(type.equals(ScriptDefinition.ValueType.PLAYER))return value instanceof Player;if(type.equals(ScriptDefinition.ValueType.CONDITION))return value instanceof Content.Condition;if(type.equals(ScriptDefinition.ValueType.DIALOGUE_REGISTRATION))return value instanceof Content.DialogueRegistration;if(type.equals(ScriptDefinition.ValueType.DURATION)){if(value instanceof java.time.Duration)return true;if(!(value instanceof String text))return false;try{nu.miguel.persona.content.Durations.parse(text);return true;}catch(RuntimeException invalid){return false;}}if(type.equals(ScriptDefinition.ValueType.LOCATION))return value instanceof Map<?,?>||value instanceof org.bukkit.Location||value instanceof String text&&Set.of("player","npc").contains(text);if(Set.of(ScriptDefinition.ValueType.STRING,ScriptDefinition.ValueType.TEXT,ScriptDefinition.ValueType.WORLD,ScriptDefinition.ValueType.MATERIAL,ScriptDefinition.ValueType.ENTITY_TYPE,ScriptDefinition.ValueType.SOUND,ScriptDefinition.ValueType.PARTICLE,ScriptDefinition.ValueType.NPC,ScriptDefinition.ValueType.NPC_INSTANCE,ScriptDefinition.ValueType.BEHAVIOR,ScriptDefinition.ValueType.DIALOGUE,ScriptDefinition.ValueType.QUEST,ScriptDefinition.ValueType.QUEST_OBJECTIVE,ScriptDefinition.ValueType.SCRIPT,ScriptDefinition.ValueType.ANCHOR).contains(type))return value instanceof String;return true;}

    private final class GraphFrame {
      private final ScriptDefinition script;
      private final Map<String, Object> inputs;
      private final EffectExecutor.Context context;
      private final Host host;
      private final int depth;
      private final LinkedHashSet<String> stack;
      private final ExecutionBudget budget;
      private final Map<String,Object> variables=new HashMap<>();
      private final Map<Endpoint, Connection> incoming = new HashMap<>();
      private final Map<Endpoint, Connection> outgoing = new HashMap<>();
      private final Map<Endpoint, Object> values = new HashMap<>();
      GraphFrame(ScriptDefinition script, Map<String, Object> inputs,
                 EffectExecutor.Context context, Host host, int depth,
                 LinkedHashSet<String> stack,ExecutionBudget budget) {
        this.script = script;
        this.inputs = inputs;
        this.context = context;
        this.host = host;
        this.depth = depth;
        this.stack = stack;
        this.budget=budget;
        script.variables().forEach((name,variable)->{if(variable.defaultValue()!=null)variables.put(name,variable.defaultValue());});
        for (Connection c : script.connections().values()) {
          incoming.put(c.to(), c);
          outgoing.put(c.from(), c);
        }
      }
      Connection outgoing(Endpoint endpoint) { return outgoing.get(endpoint); }
      CompletionStage<ScriptResult> follow(Endpoint input) {
        if(++budget.transitions>MAX_NODE_TRANSITIONS){trace(input.node(),"LIMIT",Map.of(),"Graph transition limit exceeded");host.failure("Graph transition limit of "+MAX_NODE_TRANSITIONS+" exceeded.",null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
        if (input.node().equals(ScriptDefinition.OUTPUT) &&
            input.pin().equals("exec"))
          return finish();
        ScriptDefinition.Node node = script.nodes().get(input.node());
        if (node == null)
          return CompletableFuture.completedFuture(
              ScriptResult.failed(Control.stop()));
        if (Set.of("value", "integer-to-number", "string-to-text", "to-string")
                .contains(node.type()))
          return CompletableFuture.completedFuture(
              ScriptResult.failed(Control.stop()));
        if (node.type().equals("run-script"))
          return call(input.node(), node);
        Map<String, Object> options = new LinkedHashMap<>(node.options());
        Set<String> dataPins=new LinkedHashSet<>(options.keySet());incoming.keySet().stream().filter(endpoint->endpoint.node().equals(input.node())&&!endpoint.pin().equals("exec")).map(Endpoint::pin).forEach(dataPins::add);
        for (String pin : dataPins) {
          Object value = value(input.node(), pin, new HashSet<>());
          if (value != null)
            options.put(pin, value);
        }
        trace(input.node(),"ENTER",options,null);
        if(Set.of("sequence","switch","random","gate","do-once","do-n","for","for-each","while").contains(node.type()))return flow(input,node,options);
        if(node.type().equals("choice"))return dialogueChoice(input.node(),options);
        if(node.type().equals("set-variable"))return setVariable(input.node(),node,options);
        if(node.type().startsWith("set-player-")||node.type().startsWith("set-global-npc-memory")||node.type().startsWith("set-player-npc-memory"))return persistentSet(input.node(),node,options);
        if(node.type().contains(":"))return extension(input.node(),node.type(),options);
        if(Set.of("branch","if").contains(node.type())){
          Object condition=value(input.node(),"condition",new HashSet<>());
          if(!(condition instanceof Boolean decision)){trace(input.node(),"FAILURE",options,"Branch condition must be boolean");host.failure("Branch condition must be boolean.",null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
          trace(input.node(),decision?"TRUE":"FALSE",Map.of("condition",decision),null);
          Connection next=outgoing.get(new Endpoint(input.node(),decision?"true":"false"));
          return path(next);
        }
        if(node.type().equals("goto")){String target=Objects.toString(options.get("node"),null),dialogue=Objects.toString(options.get("dialogue"),null);return CompletableFuture.completedFuture(ScriptResult.failed(dialogue==null?Control.jump(target):Control.transfer(dialogue,target)));}
        if(node.type().equals("end-dialogue"))return CompletableFuture.completedFuture(ScriptResult.failed(Control.end()));
        Step executable;
        try {
          executable = graphStep(node.type(), options);
        } catch (Throwable error) {
          trace(input.node(),"FAILURE",options,message(error));
          host.failure("Script node " + input.node() +
                           " failed: " + message(error),
                       error, context);
          return CompletableFuture.completedFuture(
              ScriptResult.failed(Control.stop()));
        }
        if(executable instanceof Command command)return graphCommand(input.node(),command);
        return execute(executable, context, host, depth + 1)
            .handle((control,error)->new Object[]{control,error})
            .thenCompose(pair -> {
              Throwable executionError=(Throwable)pair[1];
              if(executionError!=null)return commandOutcome(input.node(),false,"Script node failed.",executionError);
              Control control=(Control)pair[0];
              if (control.kind() != Kind.CONTINUE)
                return CompletableFuture.completedFuture(
                    ScriptResult.failed(control));
              Connection next =
                  outgoing.get(new Endpoint(input.node(), "success"));
              return next == null ? completedPath()
                                  : follow(next.to());
            });
      }
      private CompletionStage<ScriptResult> graphCommand(String nodeId,
                                                         Command command) {
        Object explicitTarget=command.options().get("player");
        if(PLAYER_TARGET_COMMANDS.contains(command.type())&&!(explicitTarget instanceof Player))
          return commandOutcome(nodeId,false,command.type()+" requires an explicit player input",null);
        EffectExecutor.Context commandContext = explicitTarget instanceof Player target ? context.withPlayer(target) : context;
        String validation = validate(command, commandContext);
        if (validation != null)
          return commandOutcome(nodeId, false, validation, null);
        try {
          ExpansionTypes.CommandResult result = builtin(command, commandContext);
          if (result.kind() == ExpansionTypes.CommandResult.Kind.SUCCESS)
            return commandOutcome(nodeId, true, null, null);
          if (result.kind() == ExpansionTypes.CommandResult.Kind.FAILURE)
            return commandOutcome(nodeId, false, result.message(), null);
          return CompletableFuture.completedFuture(
              ScriptResult.failed(extensionControl(result)));
        } catch (Throwable error) {
          return commandOutcome(nodeId, false, "Command failed.", error);
        }
      }
      private CompletionStage<ScriptResult> commandOutcome(String nodeId,
                                                           boolean success,
                                                           String message,
                                                           Throwable error) {
        trace(nodeId,success?"SUCCESS":"FAILURE",Map.of(),message);
        Connection next =
            outgoing.get(new Endpoint(nodeId, success ? "success" : "failure"));
        if (next != null)
          return follow(next.to());
        if (!success)
          host.failure(message == null ? "Command failed." : message, error,
                       context);
        return success ? completedPath() : CompletableFuture.completedFuture(
            ScriptResult.failed(Control.stop()));
      }
      private CompletionStage<ScriptResult>
      extension(String nodeId, String type, Map<String, Object> options) {
        ExpansionTypes.Command handler =
            plugin.api()
                .handler(ExpansionTypes.Command.class,
                         PersonaApi.canonical(type))
                .orElse(null);
        if (handler == null)
          return commandOutcome(nodeId, false,
              "Extension command is unavailable: " + type, null);
        for (ExpansionTypes.ScriptPin pin : handler.inputPins()) {
          Object value = value(nodeId, pin.name(), new HashSet<>());
          if (value == null)
            value = options.get(pin.name());
          if (value == null)
            value = pin.defaultValue();
          if (value == null && pin.required()) {
            return commandOutcome(nodeId, false,
                "Missing required input " + pin.name() + " for " + type,
                null);
          }
          if(value!=null&&!runtimeValue(ScriptDefinition.ValueType.parse(pin.valueType()),value))return commandOutcome(nodeId,false,"Extension command input "+pin.name()+" requires exact type "+pin.valueType(),null);
          if (value != null)
            options.put(pin.name(), value);
        }
        String validation =
            handler.validate(plugin.api().context(context, type), options);
        if (validation != null) {
          return commandOutcome(nodeId, false, validation, null);
        }
        CompletionStage<ExpansionTypes.CommandResult> execution;
        try {
          execution = handler.execute(plugin.api().context(context, type),
                                      Map.copyOf(options));
        } catch (Throwable error) {
          execution = CompletableFuture.failedFuture(error);
        }
        return onServer(execution)
            .handle((result, error) -> new Object[] {result, error})
            .thenCompose(pair -> {
              Throwable error = (Throwable)pair[1];
              if (error != null) {
                return commandOutcome(nodeId, false, "Command failed.", error);
              }
              ExpansionTypes.CommandResult result =
                  (ExpansionTypes.CommandResult)pair[0];
              if (result == null)
                return commandOutcome(nodeId, false,
                    "Command returned no result.", null);
              Map<String, ExpansionTypes.ScriptPin> declared = new HashMap<>();
              handler.outputPins().forEach(
                  pin -> declared.put(pin.name(), pin));
              for (var output : result.outputs().entrySet()) {
                ExpansionTypes.ScriptPin pin = declared.get(output.getKey());
                if (pin == null) {
                  return commandOutcome(nodeId, false,
                      "Extension command returned undeclared output " +
                          output.getKey(), null);
                }
                if(!runtimeValue(ScriptDefinition.ValueType.parse(pin.valueType()),output.getValue()))return commandOutcome(nodeId,false,"Extension command output "+output.getKey()+" requires exact type "+pin.valueType(),null);
                values.put(new Endpoint(nodeId, output.getKey()),
                           output.getValue());
              }
              for(ExpansionTypes.ScriptPin pin:handler.outputPins())if(pin.required()&&!result.outputs().containsKey(pin.name()))return commandOutcome(nodeId,false,"Extension command omitted required output "+pin.name(),null);
              String port =
                  result.kind() == ExpansionTypes.CommandResult.Kind.SUCCESS
                      ? "success"
                      : "failure";
              if (result.kind() != ExpansionTypes.CommandResult.Kind.SUCCESS &&
                  result.kind() != ExpansionTypes.CommandResult.Kind.FAILURE)
                return CompletableFuture.completedFuture(
                    ScriptResult.failed(extensionControl(result)));
              Connection next = outgoing.get(new Endpoint(nodeId, port));
              return next == null ? completedPath()
                                  : follow(next.to());
            });
      }
      private Control extensionControl(ExpansionTypes.CommandResult result) {
        return switch (result.kind()) {
          case JUMP -> Control.jump(result.node());
          case TRANSFER -> Control.transfer(result.dialogue(), result.node());
          case DIALOGUE_END -> Control.end();
          default -> Control.stop();
        };
      }
      private CompletionStage<ScriptResult> call(String nodeId,
                                                 ScriptDefinition.Node node) {
        String name = String.valueOf(node.options().get("script"));
        ScriptDefinition target = plugin.registry().scripts().get(name);
        if (target == null)
          return CompletableFuture.completedFuture(
              ScriptResult.failed(Control.stop()));
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<?,?> inline = node.options().get("inputs") instanceof Map<?,?> values ? values : Map.of();
        for (String pin : target.inputs().keySet()) {
          Object value = value(nodeId, pin, new HashSet<>());
          if (value == null) value = inline.get(pin);
          if (value != null)
            arguments.put(pin, value);
        }
        return ScriptEngine.this
            .run(target, arguments, context, host, depth + 1,
                 new LinkedHashSet<>(stack),budget)
            .thenCompose(result -> {
              if (result.control().kind() != Kind.CONTINUE)
                return CompletableFuture.completedFuture(
                    ScriptResult.failed(result.control()));
              result.outputs().forEach(
                  (pin, value) -> values.put(new Endpoint(nodeId, pin), value));
              Connection next = outgoing.get(new Endpoint(nodeId, "success"));
              return next == null ? completedPath()
                                  : follow(next.to());
            });
      }
      private CompletionStage<ScriptResult> finish() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
          for (var output : script.outputs().entrySet()) {
            Object value = value(ScriptDefinition.OUTPUT, output.getKey(),
                                 new HashSet<>());
            if (value == null)
              value = output.getValue().defaultValue();
            if (value == null && output.getValue().required())
              throw new IllegalStateException(
                  "Required output " + output.getKey() + " has no value");
            if (value != null && !runtimeValue(output.getValue().type(), value))
              throw new IllegalStateException("Output " + output.getKey() +
                  " requires exact type " + output.getValue().type().id());
            if (value != null)
              result.put(output.getKey(), value);
          }
          trace(ScriptDefinition.OUTPUT,"COMPLETE",result,null);
          return CompletableFuture.completedFuture(
              new ScriptResult(Control.next(), result));
        } catch (Throwable error) {
          trace(ScriptDefinition.OUTPUT,"FAILURE",Map.of(),message(error));
          host.failure("Script failed before Output: " + message(error), error,
                       context);
          return CompletableFuture.completedFuture(
              ScriptResult.failed(Control.stop()));
        }
      }
      private CompletionStage<ScriptResult> flow(Endpoint input,ScriptDefinition.Node node,Map<String,Object> options){String id=input.node(),type=node.type();
        if(type.equals("sequence")){int count=options.get("count") instanceof Number n?n.intValue():2;return sequence(id,0,count).thenCompose(result->result.control().kind()!=Kind.CONTINUE?CompletableFuture.completedFuture(result):path(outgoing.get(new Endpoint(id,"completed"))));}
        if(type.equals("switch")){Object selected=value(id,"value",new HashSet<>());String pin="case-"+pinName(selected);return path(outgoing.getOrDefault(new Endpoint(id,pin),outgoing.get(new Endpoint(id,"default"))));}
        if(type.equals("random")){List<?> weights=options.get("weights") instanceof List<?> list?list:List.of(1);double total=weights.stream().mapToDouble(value->((Number)value).doubleValue()).sum(),pick=ThreadLocalRandom.current().nextDouble(total);int selected=weights.size()-1;for(int index=0;index<weights.size();index++){pick-=((Number)weights.get(index)).doubleValue();if(pick<0){selected=index;break;}}return path(outgoing.get(new Endpoint(id,"option-"+selected)));}
        if(type.equals("gate")){StateKey key=stateKey(id,node);long open=statefulNodes.computeIfAbsent(key,ignored->Boolean.TRUE.equals(options.get("start-closed"))?0L:1L);return switch(input.pin()){case "open"->{statefulNodes.put(key,1L);yield completedPath();}case "close"->{statefulNodes.put(key,0L);yield completedPath();}case "toggle"->{statefulNodes.put(key,open==0?1L:0L);yield completedPath();}default->path(outgoing.get(new Endpoint(id,open==0?"closed":"exit")));};}
        if(type.equals("do-once")){StateKey key=stateKey(id,node);if(input.pin().equals("reset")){statefulNodes.remove(key);return completedPath();}boolean first=statefulNodes.putIfAbsent(key,1L)==null;return path(outgoing.get(new Endpoint(id,first?"completed":"skipped")));}
        if(type.equals("do-n")){StateKey key=stateKey(id,node);if(input.pin().equals("reset")){statefulNodes.remove(key);values.put(new Endpoint(id,"count"),0L);return completedPath();}Object raw=value(id,"n",new HashSet<>());long maximum=raw instanceof Number n?n.longValue():0;if(maximum<1||maximum>MAX_LOOP_ITERATIONS)return limit("Do N count",maximum);long count=statefulNodes.getOrDefault(key,0L);if(count>=maximum){values.put(new Endpoint(id,"count"),count);return path(outgoing.get(new Endpoint(id,"exhausted")));}count++;statefulNodes.put(key,count);values.put(new Endpoint(id,"count"),count);return path(outgoing.get(new Endpoint(id,"completed")));}
        if(type.equals("for")){long first=integerValue(id,"first"),last=integerValue(id,"last"),step=options.get("step") instanceof Number n?n.longValue():1;if(step==0)return limit("For step",step);long iterations=rangeIterations(first,last,step);if(iterations>MAX_LOOP_ITERATIONS)return limit("For iteration",iterations);return forLoop(id,first,step,0,iterations);}
        if(type.equals("for-each")){Object raw=value(id,"items",new HashSet<>());if(!(raw instanceof List<?> items)){host.failure("For Each items must be a list.",null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}if(items.size()>MAX_LOOP_ITERATIONS)return limit("For Each iteration",items.size());return forEach(id,items,0);}
        if(type.equals("while"))return whileLoop(id,0);
        return completedPath();
      }
      private CompletionStage<ScriptResult> sequence(String id,int index,int count){if(index>=count)return completedPath();Connection next=outgoing.get(new Endpoint(id,"then-"+index));return path(next).thenCompose(result->result.control().kind()!=Kind.CONTINUE?CompletableFuture.completedFuture(result):sequence(id,index+1,count));}
      private CompletionStage<ScriptResult> dialogueChoice(String id,Map<String,Object> options){if(!(options.get("options") instanceof List<?> raw))return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));List<ChoiceOption> choices=new ArrayList<>();List<Integer> original=new ArrayList<>();for(int index=0;index<raw.size();index++){if(!(raw.get(index) instanceof Map<?,?> option)||Boolean.FALSE.equals(option.get("enabled")))continue;choices.add(new ChoiceOption(String.valueOf(option.get("text")),new All(List.of()),List.of()));original.add(index);}if(choices.isEmpty())return completedPath();return host.choose(choices,context).thenCompose(selected->selected<0||selected>=original.size()?CompletableFuture.completedFuture(ScriptResult.failed(Control.stop())):path(outgoing.get(new Endpoint(id,"option-"+original.get(selected)))));}
      private CompletionStage<ScriptResult> forLoop(String id,long current,long step,long iteration,long total){if(iteration>=total)return path(outgoing.get(new Endpoint(id,"completed")));values.put(new Endpoint(id,"index"),current);return path(outgoing.get(new Endpoint(id,"body"))).thenCompose(result->result.control().kind()!=Kind.CONTINUE?CompletableFuture.completedFuture(result):forLoop(id,current+step,step,iteration+1,total));}
      private CompletionStage<ScriptResult> forEach(String id,List<?> items,int index){if(index>=items.size())return path(outgoing.get(new Endpoint(id,"completed")));values.put(new Endpoint(id,"index"),(long)index);values.put(new Endpoint(id,"item"),items.get(index));return path(outgoing.get(new Endpoint(id,"body"))).thenCompose(result->result.control().kind()!=Kind.CONTINUE?CompletableFuture.completedFuture(result):forEach(id,items,index+1));}
      private CompletionStage<ScriptResult> whileLoop(String id,int iteration){if(iteration>=MAX_LOOP_ITERATIONS)return limit("While iteration",iteration);Object raw=value(id,"condition",new HashSet<>());if(!(raw instanceof Boolean condition)){host.failure("While condition must be boolean.",null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}if(!condition)return path(outgoing.get(new Endpoint(id,"completed")));return path(outgoing.get(new Endpoint(id,"body"))).thenCompose(result->result.control().kind()!=Kind.CONTINUE?CompletableFuture.completedFuture(result):whileLoop(id,iteration+1));}
      private CompletionStage<ScriptResult> setVariable(String id,ScriptDefinition.Node node,Map<String,Object> options){String name=Objects.toString(node.options().get("variable"),"");Object value=options.get("value");variables.put(name,value);values.put(new Endpoint(id,"result"),value);return path(outgoing.get(new Endpoint(id,"success")));}
      private CompletionStage<ScriptResult> persistentSet(String id,ScriptDefinition.Node node,Map<String,Object> options){try{String type=node.type();Player player=context.player();if(player==null)throw new IllegalStateException(type+" requires a player");if(type.equals("set-player-flag")){plugin.states().require(player).flags().put(String.valueOf(options.get("name")),(Boolean)options.get("value"));}else if(type.equals("set-player-string")){plugin.states().require(player).variables().put(String.valueOf(options.get("name")),String.valueOf(options.get("value")));}else{String npc=Objects.toString(inputs.get("npc"),context.npc()==null?null:context.npc().id()),instance=Objects.toString(inputs.get("npc-instance"),"");UUID playerId=type.startsWith("set-player-")?player.getUniqueId():null;ScriptDefinition.ValueType valueType=ScriptDefinition.ValueType.parse(node.options().get("value-type"));plugin.memory().set(playerId,npc,instance,String.valueOf(options.get("key")),memoryType(valueType),options.get("value"),null,"script:"+script.id());}return path(outgoing.get(new Endpoint(id,"success")));}catch(Throwable failure){host.failure(failure.getMessage(),failure,context);return path(outgoing.get(new Endpoint(id,"failure")));}}
      private CompletionStage<ScriptResult> path(Connection connection){return connection==null?completedPath():follow(connection.to());}
      private CompletionStage<ScriptResult> limit(String what,long value){trace(what,"LIMIT",Map.of("value",value,"maximum",MAX_LOOP_ITERATIONS),what+" limit exceeded");host.failure(what+" limit exceeded ("+value+"; maximum "+MAX_LOOP_ITERATIONS+").",null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
      private long integerValue(String node,String pin){Object value=value(node,pin,new HashSet<>());if(!(value instanceof Number number)||number.doubleValue()!=number.longValue())throw new IllegalStateException(pin+" must be an integer");return number.longValue();}
      private long rangeIterations(long first,long last,long step){if(step>0&&first>last||step<0&&first<last)return 0;return Math.floorDiv(Math.abs(last-first),Math.abs(step))+1;}
      private StateKey stateKey(String nodeId,ScriptDefinition.Node node){String instance=Objects.toString(inputs.get("npc-instance"),context.citizensNpc()==null?"":context.citizensNpc().getUniqueId().toString());String scope=Objects.toString(node.options().get("scope"),inputs.containsKey("player")?"player":"npc");UUID player=scope.equals("player")&&context.player()!=null?context.player().getUniqueId():null;return new StateKey(script.id(),nodeId,instance,player);}
      private static String pinName(Object value){String result=String.valueOf(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+","-").replaceAll("^-+|-+$","");return result.isBlank()?"value":result;}
      private static nu.miguel.persona.api.NpcMemoryService.Type memoryType(ScriptDefinition.ValueType type){if(type.equals(ScriptDefinition.ValueType.BOOLEAN))return nu.miguel.persona.api.NpcMemoryService.Type.BOOLEAN;if(type.equals(ScriptDefinition.ValueType.NUMBER)||type.equals(ScriptDefinition.ValueType.INTEGER))return nu.miguel.persona.api.NpcMemoryService.Type.NUMBER;return nu.miguel.persona.api.NpcMemoryService.Type.STRING;}
      private CompletionStage<ScriptResult> completedPath(){return CompletableFuture.completedFuture(new ScriptResult(Control.next(),Map.of()));}
      private Object value(String node, String pin, Set<Endpoint> active) {
        Endpoint target = new Endpoint(node, pin);
        Connection wire = incoming.get(target);
        if (wire == null) {
          ScriptDefinition.Node owner = script.nodes().get(node);
          return owner == null ? null : owner.options().get(pin);
        }
        Endpoint source = wire.from();
        if (!active.add(source))
          throw new IllegalStateException("Data cycle at " + source);
        try {
          if (source.node().equals(script.entryBoundary()))
            return inputs.get(source.pin());
          if (values.containsKey(source))
            return values.get(source);
          ScriptDefinition.Node producer = script.nodes().get(source.node());
          if (producer == null)
            return null;
          if (producer.type().equals("value")){Object result=producer.options().get("value");trace(source.node(),"VALUE",Collections.singletonMap(source.pin(),result),null);return result;}
          if(producer.type().equals("get-variable")){String name=Objects.toString(producer.options().get("variable"),"");Object result=variables.getOrDefault(name,script.variables().get(name).defaultValue());trace(source.node(),"VALUE",Collections.singletonMap(source.pin(),result),null);return result;}
          if(producer.type().equals("get-player-flag")){if(context.player()==null)throw new IllegalStateException("get-player-flag requires a player");Object result=plugin.states().require(context.player()).flags().getOrDefault(String.valueOf(producer.options().get("name")),Boolean.FALSE);trace(source.node(),"VALUE",Collections.singletonMap(source.pin(),result),null);return result;}
          if(producer.type().equals("get-player-string")){if(context.player()==null)throw new IllegalStateException("get-player-string requires a player");Object result=plugin.states().require(context.player()).variables().getOrDefault(String.valueOf(producer.options().get("name")),Objects.toString(producer.options().get("default"),""));trace(source.node(),"VALUE",Collections.singletonMap(source.pin(),result),null);return result;}
          if(producer.type().startsWith("get-global-npc-memory")||producer.type().startsWith("get-player-npc-memory")){String npc=Objects.toString(inputs.get("npc"),context.npc()==null?null:context.npc().id()),instance=Objects.toString(inputs.get("npc-instance"),"");UUID playerId=producer.type().startsWith("get-player-")&&context.player()!=null?context.player().getUniqueId():null;return plugin.memory().get(playerId,npc,instance,String.valueOf(producer.options().get("key"))).map(nu.miguel.persona.api.NpcMemoryService.Value::value).orElse(producer.options().get("default"));}
          if (Set.of("integer-to-number", "string-to-text", "to-string")
                  .contains(producer.type())) {
            Object input = value(source.node(), "value", active);
            Object converted = producer.type().equals("integer-to-number") &&
                                       input instanceof Number number
                                   ? number.doubleValue()
                                   : String.valueOf(input);
            values.put(source, converted);
            trace(source.node(),"VALUE",Map.of(source.pin(),converted),null);
            return converted;
          }
          throw new IllegalStateException("Impure output " + source +
                                          " was read before execution");
        } finally {
          active.remove(source);
        }
      }
      private void trace(String node,String status,Map<String,?> values,String detail){
        String instance=Objects.toString(inputs.get("npc-instance"),context.citizensNpc()==null?"":context.citizensNpc().getUniqueId().toString());
        graphTrace(script.id(),node,status,context,values,detail,instance);
      }
      private Step graphStep(String type, Map<String, Object> options) {
        if (type.equals("wait"))
          return new Wait(nu.miguel.persona.content.Durations.parse(
              options.get("duration")));
        if(type.equals("say")){Map<String,String> translations=new LinkedHashMap<>();if(options.get("translations") instanceof Map<?,?> values)values.forEach((key,value)->translations.put(String.valueOf(key),String.valueOf(value)));List<WeightedText> variants=new ArrayList<>();if(options.get("variants") instanceof List<?> values)for(Object raw:values)if(raw instanceof Map<?,?> value)variants.add(new WeightedText(String.valueOf(value.get("text")),value.get("weight") instanceof Number n?n.intValue():1));return new Say(options.get("text")==null?null:String.valueOf(options.get("text")),options.get("text-key")==null?null:String.valueOf(options.get("text-key")),translations,variants,options.containsKey("delay")?nu.miguel.persona.content.Durations.parse(options.get("delay")):java.time.Duration.ZERO);}
        if(type.equals("stop"))return new Stop();
        String key =
            type.contains(":") ? PersonaApi.canonical(type) : "persona:" + type;
        return new Command(key, options, List.of(), List.of());
      }
    }
}
