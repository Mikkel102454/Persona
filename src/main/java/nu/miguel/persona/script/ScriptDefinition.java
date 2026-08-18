package nu.miguel.persona.script;

import java.util.*;

/** Immutable, validated reusable-script graph loaded from scripts.yml format 2. */
public record ScriptDefinition(String id, Map<String, Parameter> inputs, Map<String, Parameter> outputs,
                               Map<String, Node> nodes, Map<String, Connection> connections) {
    public static final String INPUT = "$input";
    public static final String OUTPUT = "$output";

    public ScriptDefinition {
        inputs = immutable(inputs);
        outputs = immutable(outputs);
        nodes = immutable(nodes);
        connections = immutable(connections);
    }

    private static <T> Map<String, T> immutable(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source == null ? Map.of() : source));
    }

    public record Parameter(ValueType type, boolean required, Object defaultValue) {}

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
        public static final ValueType BOOLEAN=builtin("boolean"),INTEGER=builtin("integer"),NUMBER=builtin("number"),STRING=builtin("string"),TEXT=builtin("text"),DURATION=builtin("duration"),LOCATION=builtin("location"),WORLD=builtin("world"),MATERIAL=builtin("material"),ENTITY_TYPE=builtin("entity-type"),SOUND=builtin("sound"),PARTICLE=builtin("particle"),NPC=builtin("npc"),NPC_INSTANCE=builtin("npc-instance"),BEHAVIOR=builtin("behavior"),DIALOGUE=builtin("dialogue"),QUEST=builtin("quest"),QUEST_OBJECTIVE=builtin("quest-objective"),SCRIPT=builtin("script"),ANCHOR=builtin("anchor");
        public ValueType {if(id==null||!id.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid script value type "+id);}
        private static ValueType builtin(String id){ValueType type=new ValueType(id);BUILTINS.put(id,type);return type;}
        public static ValueType parse(Object raw){String value=Objects.toString(raw,"");ValueType builtin=BUILTINS.get(value);if(builtin!=null)return builtin;if(value.contains(":"))return new ValueType(value);throw new IllegalArgumentException("unknown script value type "+value);}
        public boolean domainId(){return Set.of(WORLD,MATERIAL,ENTITY_TYPE,SOUND,PARTICLE,NPC,NPC_INSTANCE,BEHAVIOR,DIALOGUE,QUEST,QUEST_OBJECTIVE,SCRIPT,ANCHOR).contains(this);}
        @Override public String toString(){return id;}
    }
}
