package nu.miguel.persona.script;

import nu.miguel.persona.Main;
import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.content.Content.*;
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
    public static final int MAX_DEPTH=32;
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
            if(step instanceof RunScript r){ScriptDefinition target=plugin.registry().scripts().get(r.script());if(target==null){host.failure("Reusable script is unavailable: "+r.script(),null,context);return CompletableFuture.completedFuture(Control.stop());}return run(target,r.inputs(),context,host,depth+1,new LinkedHashSet<>()).thenApply(ScriptResult::control);}
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
        return run(script,inputs,context,new Host(){},0,new LinkedHashSet<>());
    }
    public CompletionStage<ScriptResult> run(ScriptDefinition script,Map<String,Object> inputs,EffectExecutor.Context context,Host host){
        return run(script,inputs,context,host,0,new LinkedHashSet<>());
    }
    private CompletionStage<ScriptResult> run(ScriptDefinition script,Map<String,Object> supplied,EffectExecutor.Context context,Host host,int depth,LinkedHashSet<String> stack){
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
        GraphFrame frame=new GraphFrame(script,inputs,context,host,depth,stack);
        Connection first=frame.outgoing(new Endpoint(ScriptDefinition.INPUT,"exec"));
        if(first==null){stack.remove(script.id());return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
        return frame.follow(first.to()).handle((result,error)->{stack.remove(script.id());if(error==null)return result;host.failure("Script failed: "+message(error),error,context);return ScriptResult.failed(Control.stop());});
    }
    private static boolean runtimeValue(ScriptDefinition.ValueType type,Object value){if(type.equals(ScriptDefinition.ValueType.BOOLEAN))return value instanceof Boolean;if(type.equals(ScriptDefinition.ValueType.INTEGER))return value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long;if(type.equals(ScriptDefinition.ValueType.NUMBER))return value instanceof Number;if(type.equals(ScriptDefinition.ValueType.DURATION)){if(value instanceof java.time.Duration)return true;if(!(value instanceof String text))return false;try{nu.miguel.persona.content.Durations.parse(text);return true;}catch(RuntimeException invalid){return false;}}if(type.equals(ScriptDefinition.ValueType.LOCATION))return value instanceof Map<?,?>||value instanceof org.bukkit.Location||value instanceof String text&&Set.of("player","npc").contains(text);if(Set.of(ScriptDefinition.ValueType.STRING,ScriptDefinition.ValueType.TEXT,ScriptDefinition.ValueType.WORLD,ScriptDefinition.ValueType.MATERIAL,ScriptDefinition.ValueType.ENTITY_TYPE,ScriptDefinition.ValueType.SOUND,ScriptDefinition.ValueType.PARTICLE,ScriptDefinition.ValueType.NPC,ScriptDefinition.ValueType.NPC_INSTANCE,ScriptDefinition.ValueType.BEHAVIOR,ScriptDefinition.ValueType.DIALOGUE,ScriptDefinition.ValueType.QUEST,ScriptDefinition.ValueType.QUEST_OBJECTIVE,ScriptDefinition.ValueType.SCRIPT,ScriptDefinition.ValueType.ANCHOR).contains(type))return value instanceof String;return true;}

    private final class GraphFrame {
      private final ScriptDefinition script;
      private final Map<String, Object> inputs;
      private final EffectExecutor.Context context;
      private final Host host;
      private final int depth;
      private final LinkedHashSet<String> stack;
      private final Map<Endpoint, Connection> incoming = new HashMap<>();
      private final Map<Endpoint, Connection> outgoing = new HashMap<>();
      private final Map<Endpoint, Object> values = new HashMap<>();
      GraphFrame(ScriptDefinition script, Map<String, Object> inputs,
                 EffectExecutor.Context context, Host host, int depth,
                 LinkedHashSet<String> stack) {
        this.script = script;
        this.inputs = inputs;
        this.context = context;
        this.host = host;
        this.depth = depth;
        this.stack = stack;
        for (Connection c : script.connections().values()) {
          incoming.put(c.to(), c);
          outgoing.put(c.from(), c);
        }
      }
      Connection outgoing(Endpoint endpoint) { return outgoing.get(endpoint); }
      CompletionStage<ScriptResult> follow(Endpoint input) {
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
        if(node.type().contains(":"))return extension(input.node(),node.type(),options);
        if(Set.of("branch","if").contains(node.type())){
          Object condition=value(input.node(),"condition",new HashSet<>());
          if(!(condition instanceof Boolean decision)){host.failure("Branch condition must be boolean.",null,context);return CompletableFuture.completedFuture(ScriptResult.failed(Control.stop()));}
          Connection next=outgoing.get(new Endpoint(input.node(),decision?"true":"false"));
          return next==null?CompletableFuture.completedFuture(ScriptResult.failed(Control.stop())):follow(next.to());
        }
        Step executable;
        try {
          executable = graphStep(node.type(), options);
        } catch (Throwable error) {
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
              return next == null ? CompletableFuture.completedFuture(
                                        ScriptResult.failed(Control.stop()))
                                  : follow(next.to());
            });
      }
      private CompletionStage<ScriptResult> graphCommand(String nodeId,
                                                         Command command) {
        String validation = validate(command, context);
        if (validation != null)
          return commandOutcome(nodeId, false, validation, null);
        try {
          ExpansionTypes.CommandResult result = builtin(command, context);
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
        Connection next =
            outgoing.get(new Endpoint(nodeId, success ? "success" : "failure"));
        if (next != null)
          return follow(next.to());
        if (!success)
          host.failure(message == null ? "Command failed." : message, error,
                       context);
        return CompletableFuture.completedFuture(
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
              return next == null ? CompletableFuture.completedFuture(
                                        ScriptResult.failed(Control.stop()))
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
                 new LinkedHashSet<>(stack))
            .thenCompose(result -> {
              if (result.control().kind() != Kind.CONTINUE)
                return CompletableFuture.completedFuture(
                    ScriptResult.failed(result.control()));
              result.outputs().forEach(
                  (pin, value) -> values.put(new Endpoint(nodeId, pin), value));
              Connection next = outgoing.get(new Endpoint(nodeId, "success"));
              return next == null ? CompletableFuture.completedFuture(
                                        ScriptResult.failed(Control.stop()))
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
          return CompletableFuture.completedFuture(
              new ScriptResult(Control.next(), result));
        } catch (Throwable error) {
          host.failure("Script failed before Output: " + message(error), error,
                       context);
          return CompletableFuture.completedFuture(
              ScriptResult.failed(Control.stop()));
        }
      }
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
          if (source.node().equals(ScriptDefinition.INPUT))
            return inputs.get(source.pin());
          if (values.containsKey(source))
            return values.get(source);
          ScriptDefinition.Node producer = script.nodes().get(source.node());
          if (producer == null)
            return null;
          if (producer.type().equals("value"))
            return producer.options().get("value");
          if (Set.of("integer-to-number", "string-to-text", "to-string")
                  .contains(producer.type())) {
            Object input = value(source.node(), "value", active);
            Object converted = producer.type().equals("integer-to-number") &&
                                       input instanceof Number number
                                   ? number.doubleValue()
                                   : String.valueOf(input);
            values.put(source, converted);
            return converted;
          }
          throw new IllegalStateException("Impure output " + source +
                                          " was read before execution");
        } finally {
          active.remove(source);
        }
      }
      private Step graphStep(String type, Map<String, Object> options) {
        if (type.equals("wait"))
          return new Wait(nu.miguel.persona.content.Durations.parse(
              options.get("duration")));
        if(type.equals("say"))return new Say(String.valueOf(options.get("text")),null,Map.of(),List.of(),options.containsKey("delay")?nu.miguel.persona.content.Durations.parse(options.get("delay")):java.time.Duration.ZERO);
        if(type.equals("stop"))return new Stop();
        String key =
            type.contains(":") ? PersonaApi.canonical(type) : "persona:" + type;
        return new Command(key, options, List.of(), List.of());
      }
    }
}
