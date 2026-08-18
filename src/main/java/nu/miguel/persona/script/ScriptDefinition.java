package nu.miguel.persona.script;

import java.util.*;

/**
 * Immutable, validated content-version 2 graph.
 *
 * <p>Reusable scripts use the {@code $input}/{@code $output} boundaries while
 * host-owned NPC, dialogue, and quest graphs use {@code $event}.  Keeping both
 * shapes in one descriptor is intentional: the runtime and editor must not
 * grow subtly different graph languages for each content kind.</p>
 */
public record ScriptDefinition(String id, Boundary boundary,
                               Map<String, Parameter> inputs, Map<String, Parameter> outputs,
                               Map<String, Variable> variables,
                               Map<String, Node> nodes, Map<String, Connection> connections) {
    public static final String INPUT = "$input";
    public static final String OUTPUT = "$output";
    public static final String EVENT = "$event";

    public enum Boundary { REUSABLE, EVENT }

    public ScriptDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("graph id is required");
        boundary = Objects.requireNonNull(boundary, "graph boundary");
        inputs = immutable(inputs);
        outputs = immutable(outputs);
        variables = immutable(variables);
        nodes = immutable(nodes);
        connections = immutable(connections);
        if (boundary == Boundary.EVENT && !outputs.isEmpty())
            throw new IllegalArgumentException("host event graphs cannot declare $output parameters");
    }

    public ScriptDefinition(String id, Map<String, Parameter> inputs, Map<String, Parameter> outputs,
                            Map<String, Node> nodes, Map<String, Connection> connections) {
        this(id, Boundary.REUSABLE, inputs, outputs, Map.of(), nodes, connections);
    }

    public static ScriptDefinition event(String id, Map<String, Parameter> eventOutputs,
                                         Map<String, Variable> variables,
                                         Map<String, Node> nodes, Map<String, Connection> connections) {
        return new ScriptDefinition(id, Boundary.EVENT, eventOutputs, Map.of(), variables, nodes, connections);
    }

    public String entryBoundary() { return boundary == Boundary.EVENT ? EVENT : INPUT; }
    public String exitBoundary() { return boundary == Boundary.REUSABLE ? OUTPUT : null; }

    private static <T> Map<String, T> immutable(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source == null ? Map.of() : source));
    }

    public record Parameter(ValueType type, boolean required, Object defaultValue) {}

    public record Variable(ValueType type, Object defaultValue) {
        public Variable { Objects.requireNonNull(type, "variable type"); }
    }

    public record Node(String type, Map<String, Object> options) {
        public Node { options = immutable(options); }
    }

    public record Endpoint(String node, String pin) {
        public Endpoint {
            if (node == null || node.isBlank() || pin == null || pin.isBlank())
                throw new IllegalArgumentException("connection endpoint must be node.pin");
        }
        public static Endpoint parse(Object raw) {
            String value = Objects.toString(raw, "");
            int split = value.lastIndexOf('.');
            if (split < 1 || split == value.length() - 1)
                throw new IllegalArgumentException("connection endpoint must be node.pin: " + value);
            return new Endpoint(value.substring(0, split), value.substring(split + 1));
        }
        @Override public String toString() { return node + "." + pin; }
    }

    public record Connection(Endpoint from, Endpoint to) {}

    public record ValueType(String id) {
        private static final Map<String,ValueType> BUILTINS=new LinkedHashMap<>();
        public static final ValueType BOOLEAN=builtin("boolean"),INTEGER=builtin("integer"),NUMBER=builtin("number"),STRING=builtin("string"),TEXT=builtin("text"),DURATION=builtin("duration"),LOCATION=builtin("location"),WORLD=builtin("world"),MATERIAL=builtin("material"),ENTITY_TYPE=builtin("entity-type"),SOUND=builtin("sound"),PARTICLE=builtin("particle"),PLAYER=builtin("player"),CONDITION=builtin("condition"),DIALOGUE_REGISTRATION=builtin("dialogue-registration"),NPC=builtin("npc"),NPC_INSTANCE=builtin("npc-instance"),BEHAVIOR=builtin("behavior"),DIALOGUE=builtin("dialogue"),QUEST=builtin("quest"),QUEST_OBJECTIVE=builtin("quest-objective"),SCRIPT=builtin("script"),ANCHOR=builtin("anchor");
        public ValueType {if(id==null||!id.matches("(?:list:)*[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid script value type "+id);}
        private static ValueType builtin(String id){ValueType type=new ValueType(id);BUILTINS.put(id,type);return type;}
        public static ValueType parse(Object raw){String value=Objects.toString(raw,"");ValueType builtin=BUILTINS.get(value);if(builtin!=null)return builtin;if(value.startsWith("list:")){ValueType element=parse(value.substring(5));if(element.list())throw new IllegalArgumentException("nested list value types are not supported: "+value);return new ValueType("list:"+element.id());}if(value.contains(":"))return new ValueType(value);throw new IllegalArgumentException("unknown script value type "+value);}
        public boolean list(){return id.startsWith("list:");}
        public ValueType elementType(){if(!list())throw new IllegalStateException(id+" is not a list type");return parse(id.substring(5));}
        public boolean domainId(){return Set.of(WORLD,MATERIAL,ENTITY_TYPE,SOUND,PARTICLE,NPC,NPC_INSTANCE,BEHAVIOR,DIALOGUE,QUEST,QUEST_OBJECTIVE,SCRIPT,ANCHOR).contains(this);}
        @Override public String toString(){return id;}
    }
}
