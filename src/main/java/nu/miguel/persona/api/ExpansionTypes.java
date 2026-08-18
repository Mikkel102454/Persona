package nu.miguel.persona.api;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import nu.miguel.persona.behavior.BehaviorStatus;

/** Persona 2.x extension contracts. Commands are parsed at load and awaited at execution. */
public final class ExpansionTypes {
    private ExpansionTypes() {}
    public record ObjectiveDefinition(long required, Map<String,Object> data) {
        public ObjectiveDefinition { if (required < 1) throw new IllegalArgumentException("required must be positive"); data=Map.copyOf(data); }
    }
    public record CommandResult(Kind kind,String message,String dialogue,String node,Map<String,Object> outputs) {
        public CommandResult { outputs=outputs==null?Map.of():Map.copyOf(outputs); }
        public CommandResult(Kind kind,String message,String dialogue,String node){this(kind,message,dialogue,node,Map.of());}
        public enum Kind { SUCCESS,FAILURE,JUMP,TRANSFER,DIALOGUE_END,STOP }
        public static CommandResult success(){return new CommandResult(Kind.SUCCESS,null,null,null,Map.of());}
        public static CommandResult success(Map<String,Object> outputs){return new CommandResult(Kind.SUCCESS,null,null,null,outputs);}
        public static CommandResult failure(String message){return new CommandResult(Kind.FAILURE,message,null,null);}
        public static CommandResult jump(String node){return new CommandResult(Kind.JUMP,null,null,node);}
        public static CommandResult transfer(String dialogue,String node){return new CommandResult(Kind.TRANSFER,null,dialogue,node);}
        public static CommandResult dialogueEnd(){return new CommandResult(Kind.DIALOGUE_END,null,null,null);}
        public static CommandResult stop(){return new CommandResult(Kind.STOP,null,null,null);}
    }
    /** Data-only pin declaration included in the signed editor metadata snapshot. */
    public record ScriptPin(String name,String valueType,boolean required,Object defaultValue) {
        public ScriptPin {if(name==null||!name.matches("[A-Za-z_][A-Za-z0-9_-]*"))throw new IllegalArgumentException("invalid pin name "+name);if(valueType==null||!valueType.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid nominal value type "+valueType);}
    }
    @FunctionalInterface public interface Parser { Map<String,Object> parse(Map<String,Object> yaml); }
    public interface Condition extends EditorSchemaProvider { default Map<String,Object> parse(Map<String,Object> yaml){return Map.copyOf(yaml);} boolean test(PersonaContext context,Map<String,Object> data); default Map<String,Object> editorSchema(){return Map.of();} }
    public interface Command extends EditorSchemaProvider { default Map<String,Object> parse(Map<String,Object> yaml){return Map.copyOf(yaml);} default String validate(PersonaContext context,Map<String,Object> data){return null;} CompletionStage<CommandResult> execute(PersonaContext context,Map<String,Object> data); default java.util.List<ScriptPin> inputPins(){return java.util.List.of();} default java.util.List<ScriptPin> outputPins(){return java.util.List.of();} default Map<String,Map<String,Object>> nominalValueTypes(){return Map.of();} default Map<String,Object> editorSchema(){return Map.of();} }
    public interface Placeholder extends EditorSchemaProvider { String resolve(PersonaContext context,String argument); default Map<String,Object> editorSchema(){return Map.of();} }
    public interface Objective extends EditorSchemaProvider { ObjectiveDefinition parse(Map<String,Object> yaml); default Map<String,Object> editorSchema(){return Map.of();} }
    public interface BehaviorCondition extends EditorSchemaProvider {
        default Map<String,Object> parse(Map<String,Object> yaml){return Map.copyOf(yaml);}
        boolean test(BehaviorContext context,Map<String,Object> data);
        default Map<String,Object> schema(){return Map.of();}
        default BehaviorNodeMetadata metadata(){return new BehaviorNodeMetadata(null,null,null,schema());}
        default Map<String,Object> editorSchema(){return metadata().schema();}
    }
    public interface BehaviorAction extends EditorSchemaProvider {
        default Map<String,Object> parse(Map<String,Object> yaml){return Map.copyOf(yaml);}
        CompletionStage<BehaviorStatus> execute(BehaviorContext context,Map<String,Object> data);
        /** API 2.1 entry point. The default preserves binary/source compatibility with 2.0. */
        default CompletionStage<BehaviorStatus> execute(BehaviorContext context,Map<String,Object> data,CancellationToken cancellation){return execute(context,data);}
        /** Legacy callback, invoked at most once for a started execution. */
        default void cancel(BehaviorContext context){}
        default Map<String,Object> schema(){return Map.of();}
        default BehaviorNodeMetadata metadata(){return new BehaviorNodeMetadata(null,null,null,schema());}
        default Map<String,Object> editorSchema(){return metadata().schema();}
    }
}
