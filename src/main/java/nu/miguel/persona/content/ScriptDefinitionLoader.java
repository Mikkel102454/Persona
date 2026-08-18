package nu.miguel.persona.content;

import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.script.ScriptDefinition;
import nu.miguel.persona.script.ScriptDefinition.*;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.*;

/** Parser and structural/type validator for the scripts.yml format-2 graph language. */
final class ScriptDefinitionLoader {
    private final PersonaApi api;
    ScriptDefinitionLoader(PersonaApi api){this.api=api;}
    private static final Set<String> DESCRIPTOR_KEYS=Set.of("inputs","outputs","nodes","connections");
    private static final Map<String,ValueType> COMMAND_INPUTS=Map.ofEntries(
            Map.entry("quest",ValueType.QUEST), Map.entry("objective",ValueType.QUEST_OBJECTIVE),
            Map.entry("amount",ValueType.INTEGER), Map.entry("material",ValueType.MATERIAL),
            Map.entry("entity",ValueType.ENTITY_TYPE), Map.entry("sound",ValueType.SOUND),
            Map.entry("particle",ValueType.PARTICLE), Map.entry("text",ValueType.TEXT),
            Map.entry("duration",ValueType.DURATION), Map.entry("location",ValueType.LOCATION),
            Map.entry("value",ValueType.STRING),Map.entry("count",ValueType.INTEGER),
            Map.entry("amplifier",ValueType.INTEGER),Map.entry("radius",ValueType.NUMBER),
            Map.entry("volume",ValueType.NUMBER),Map.entry("pitch",ValueType.NUMBER),
            Map.entry("offset-x",ValueType.NUMBER),Map.entry("offset-y",ValueType.NUMBER),
            Map.entry("offset-z",ValueType.NUMBER),Map.entry("extra",ValueType.NUMBER),
            Map.entry("title",ValueType.TEXT),Map.entry("subtitle",ValueType.TEXT),
            Map.entry("fade-in",ValueType.DURATION),Map.entry("stay",ValueType.DURATION),
            Map.entry("fade-out",ValueType.DURATION));

    Map<String,ScriptDefinition> parse(ConfigurationSection scripts) {
        Map<String,ScriptDefinition> result=new LinkedHashMap<>();
        for(String id:scripts.getKeys(false)) {
            Object raw=scripts.get(id);
            if(raw instanceof List<?>) throw new IllegalArgumentException("script "+id+" uses obsolete list form; scripts.yml content-version 2 requires inputs, outputs, nodes, and connections (see SCRIPT_FORMAT_2_MIGRATION.md)");
            ConfigurationSection descriptor=section(raw,"script "+id);
            Validation.keys(descriptor,DESCRIPTOR_KEYS);
            result.put(id,new ScriptDefinition(id,parameters(descriptor.getConfigurationSection("inputs"),"input"),
                    parameters(descriptor.getConfigurationSection("outputs"),"output"),
                    nodes(descriptor.getConfigurationSection("nodes")),connections(descriptor.getConfigurationSection("connections"))));
        }
        result.values().forEach(definition->validate(definition,result));
        result.keySet().forEach(id->rejectRecursion(id,result,new LinkedHashSet<>()));
        return Collections.unmodifiableMap(result);
    }

    private Map<String,Parameter> parameters(ConfigurationSection section,String kind) {
        if(section==null)return Map.of();Map<String,Parameter> out=new LinkedHashMap<>();
        for(String name:section.getKeys(false)) {
            requireKey(name,kind+" parameter");ConfigurationSection value=section(section.get(name),kind+" "+name);
            Validation.keys(value,Set.of("type","required","default"));
            ValueType type=valueType(required(value.get("type"),kind+" "+name+" type"),kind+" "+name+" type");
            boolean required=value.getBoolean("required",false);Object defaultValue=value.get("default");
            if(defaultValue!=null)validateLiteral(type,defaultValue,kind+" "+name+" default");
            if(required&&defaultValue!=null) throw new IllegalArgumentException(kind+" "+name+" cannot be required and have a default");
            out.put(name,new Parameter(type,required,defaultValue));
        }return out;
    }

    private Map<String,Node> nodes(ConfigurationSection section) {
        if(section==null)return Map.of();Map<String,Node> out=new LinkedHashMap<>();
        for(String id:section.getKeys(false)) {
            requireKey(id,"script node");if(id.equals(ScriptDefinition.INPUT)||id.equals(ScriptDefinition.OUTPUT))throw new IllegalArgumentException(id+" is reserved for script boundaries");
            ConfigurationSection value=section(section.get(id),"node "+id);String type=Objects.toString(required(value.get("type"),"node "+id+" type"));
            Map<String,Object> options=new LinkedHashMap<>();value.getValues(false).forEach((key,item)->{if(!key.equals("type"))options.put(key,plain(item));});
            if(type.equals("value")){ValueType valueType=valueType(required(options.get("value-type"),"node "+id+" value-type"),"node "+id+" value-type");validateLiteral(valueType,required(options.get("value"),"node "+id+" value"),"node "+id+" value");}
            out.put(id,new Node(type,options));
        }return out;
    }

    private Map<String,Connection> connections(ConfigurationSection section) {
        if(section==null)return Map.of();Map<String,Connection> out=new LinkedHashMap<>();Set<String> pairs=new HashSet<>();
        for(String id:section.getKeys(false)) {
            requireKey(id,"connection");ConfigurationSection value=section(section.get(id),"connection "+id);Validation.keys(value,Set.of("from","to"));
            Endpoint from=Endpoint.parse(required(value.get("from"),"connection "+id+" from"));Endpoint to=Endpoint.parse(required(value.get("to"),"connection "+id+" to"));
            if(!pairs.add(from+" -> "+to))throw new IllegalArgumentException("duplicate connection "+from+" -> "+to);
            out.put(id,new Connection(from,to));
        }return out;
    }

    private void validate(ScriptDefinition script,Map<String,ScriptDefinition> all) {
        Map<Endpoint,Port> ports=ports(script,all);Map<Endpoint,String> incoming=new HashMap<>(),executionOutgoing=new HashMap<>();
        Map<String,Set<String>> dataGraph=new HashMap<>(),execGraph=new HashMap<>();
        for(var entry:script.connections().entrySet()) {
            String id=entry.getKey();Connection connection=entry.getValue();Port from=ports.get(connection.from()),to=ports.get(connection.to());
            if(from==null)throw new IllegalArgumentException("script "+script.id()+" connection "+id+" has missing endpoint "+connection.from());
            if(to==null)throw new IllegalArgumentException("script "+script.id()+" connection "+id+" has missing endpoint "+connection.to());
            if(!from.output||to.output)throw new IllegalArgumentException("script "+script.id()+" connection "+id+" must connect output to input");
            if(from.execution!=to.execution)throw new IllegalArgumentException("script "+script.id()+" connection "+id+" mixes execution and data pins");
            if(!from.execution&&from.type!=to.type)throw new IllegalArgumentException("script "+script.id()+" connection "+id+" requires exact type "+to.type.id()+", got "+from.type.id());
            if(incoming.putIfAbsent(connection.to(),id)!=null)throw new IllegalArgumentException("script "+script.id()+" input "+connection.to()+" has more than one connection");
            if(from.execution&&executionOutgoing.putIfAbsent(connection.from(),id)!=null)throw new IllegalArgumentException("script "+script.id()+" execution output "+connection.from()+" has more than one connection");
            (from.execution?execGraph:dataGraph).computeIfAbsent(connection.from().node(),ignored->new LinkedHashSet<>()).add(connection.to().node());
        }
        rejectCycles(dataGraph,"data",script.id());rejectCycles(execGraph,"execution",script.id());
        if(!incoming.containsKey(new Endpoint(ScriptDefinition.OUTPUT,"exec")))throw new IllegalArgumentException("script "+script.id()+" has no execution path to $output.exec");
        Set<String> execReachable=reachable(execGraph,ScriptDefinition.INPUT);
        if(!execReachable.contains(ScriptDefinition.OUTPUT))throw new IllegalArgumentException("script "+script.id()+" $output.exec is unreachable");
        for(var output:script.outputs().entrySet())if(output.getValue().required()&&!incoming.containsKey(new Endpoint(ScriptDefinition.OUTPUT,output.getKey())))
            throw new IllegalArgumentException("script "+script.id()+" required output "+output.getKey()+" is unwired");
        for(var entry:ports.entrySet())if(entry.getValue().required&&!entry.getValue().output&&!entry.getValue().execution&&!incoming.containsKey(entry.getKey())){Node owner=script.nodes().get(entry.getKey().node());if(owner==null||!inline(owner,entry.getKey().pin()))throw new IllegalArgumentException("script "+script.id()+" required input "+entry.getKey()+" is unwired and has no inline default");}
        for(Connection connection:script.connections().values())if(!ports.get(connection.from()).execution&&!pure(script.nodes().get(connection.from().node()))){String producer=connection.from().node(),consumer=connection.to().node();if(producer.equals(consumer)||!execReachable.contains(producer)||pathAvoiding(execGraph,ScriptDefinition.INPUT,consumer,producer))throw new IllegalArgumentException("script "+script.id()+" reads impure output "+connection.from()+" before execution");}
    }

    private Map<Endpoint,Port> ports(ScriptDefinition script,Map<String,ScriptDefinition> all) {
        Map<Endpoint,Port> out=new HashMap<>();out.put(new Endpoint(ScriptDefinition.INPUT,"exec"),Port.exec(true));out.put(new Endpoint(ScriptDefinition.OUTPUT,"exec"),Port.exec(false));
        script.inputs().forEach((name,p)->out.put(new Endpoint(ScriptDefinition.INPUT,name),Port.data(true,p.type())));
        script.outputs().forEach((name,p)->out.put(new Endpoint(ScriptDefinition.OUTPUT,name),Port.data(false,p.type())));
        script.nodes().forEach((id,node)->nodePorts(id,node,all).forEach((pin,port)->out.put(new Endpoint(id,pin),port)));
        return out;
    }

    private Map<String,Port> nodePorts(String id,Node node,Map<String,ScriptDefinition> all) {
        Map<String,Port> out=new HashMap<>();String type=node.type();
        if(type.equals("value")){out.put("value",Port.data(true,valueType(node.options().get("value-type"),"node "+id+" value-type")));return out;}
        if(type.equals("integer-to-number")){out.put("value",Port.requiredData(false,ValueType.INTEGER));out.put("result",Port.data(true,ValueType.NUMBER));return out;}
        if(type.equals("string-to-text")){out.put("value",Port.requiredData(false,ValueType.STRING));out.put("result",Port.data(true,ValueType.TEXT));return out;}
        if(type.equals("to-string")){ValueType source=valueType(required(node.options().get("value-type"),"node "+id+" value-type"),"node "+id+" value-type");if(!source.domainId()&&!source.id().contains(":")&&source!=ValueType.BOOLEAN&&source!=ValueType.INTEGER&&source!=ValueType.NUMBER)throw new IllegalArgumentException("node "+id+" to-string does not allow "+source.id());out.put("value",Port.requiredData(false,source));out.put("result",Port.data(true,ValueType.STRING));return out;}
        out.put("exec",Port.exec(false));
        if(Set.of("branch","if").contains(type)){out.put("true",Port.exec(true));out.put("false",Port.exec(true));out.put("condition",Port.requiredData(false,ValueType.BOOLEAN));return out;}
        if(type.equals("stop"))return out;
        out.put("success",Port.exec(true));out.put("failure",Port.exec(true));
        if(type.equals("run-script")){String target=Objects.toString(required(node.options().get("script"),"node "+id+" script"));ScriptDefinition call=all.get(target);if(call==null)throw new IllegalArgumentException("node "+id+" references missing script "+target);Object rawInputs=required(node.options().get("inputs"),"node "+id+" inputs mapping");if(!(rawInputs instanceof Map<?,?> inputs))throw new IllegalArgumentException("node "+id+" inputs must be a mapping");for(var input:inputs.entrySet()){String name=String.valueOf(input.getKey());Parameter parameter=call.inputs().get(name);if(parameter==null)throw new IllegalArgumentException("node "+id+" has unknown input "+name);validateLiteral(parameter.type(),input.getValue(),"node "+id+" input "+name);}call.inputs().forEach((name,p)->out.put(name,p.required()?Port.requiredData(false,p.type()):Port.data(false,p.type())));call.outputs().forEach((name,p)->out.put(name,Port.data(true,p.type())));}
        else if(type.equals("wait"))out.put("duration",Port.requiredData(false,ValueType.DURATION));
        else if(type.equals("say")){out.put("text",Port.requiredData(false,ValueType.TEXT));out.put("delay",Port.data(false,ValueType.DURATION));}
        else if(type.contains(":")){if(api==null)throw new IllegalArgumentException("node "+id+" references unavailable extension command "+type);ExpansionTypes.Command command=api.handler(ExpansionTypes.Command.class,PersonaApi.canonical(type)).orElseThrow(()->new IllegalArgumentException("node "+id+" references unavailable extension command "+type));command.inputPins().forEach(pin->{ValueType pinType=valueType(pin.valueType(),"extension input "+pin.name());out.put(pin.name(),pin.required()?Port.requiredData(false,pinType):Port.data(false,pinType));});command.outputPins().forEach(pin->out.put(pin.name(),Port.data(true,valueType(pin.valueType(),"extension output "+pin.name()))));}
        else ContentLoader.commandOptionKeys(type).forEach(name->{ValueType pinType=commandInputType(type,name);out.put(name,requiredCommandPin(type,name)?Port.requiredData(false,pinType):Port.data(false,pinType));});
        return out;
    }
    private static boolean pure(Node node){return node==null||Set.of("value","integer-to-number","string-to-text","to-string").contains(node.type());}
    private ValueType valueType(Object raw,String what){ValueType type=ValueType.parse(raw);if(type.id().contains(":")&&(api==null||!api.scriptValueTypes().contains(type.id())))throw new IllegalArgumentException(what+" uses undeclared extension value type "+type.id());return type;}
    private static boolean inline(Node node,String pin){if(node.options().containsKey(pin))return true;if(!node.type().equals("run-script"))return false;Object inputs=node.options().get("inputs");return inputs instanceof Map<?,?> map&&map.containsKey(pin);}
    private static boolean requiredCommandPin(String type,String pin){return switch(type){case "start-quest","finish-quest"->pin.equals("quest");case "deliver-items"->Set.of("quest","objective").contains(pin);case "give-item","take-item","set-block"->pin.equals("material");case "message","action-bar","broadcast","npc-speak"->pin.equals("text");case "play-sound"->pin.equals("sound");case "particle"->pin.equals("particle");case "run-command"->pin.equals("command");case "spawn-entity"->pin.equals("entity");default->false;};}
    private static ValueType commandInputType(String command,String pin){if(command.equals("set-flag")&&pin.equals("value")||Set.of("ambient","particles").contains(pin))return ValueType.BOOLEAN;return COMMAND_INPUTS.getOrDefault(pin,ValueType.STRING);}
    private static void rejectCycles(Map<String,Set<String>> graph,String kind,String script){Set<String> done=new HashSet<>(),active=new HashSet<>();for(String node:graph.keySet())visit(node,graph,done,active,kind,script);}
    private static void visit(String node,Map<String,Set<String>> graph,Set<String> done,Set<String> active,String kind,String script){if(done.contains(node))return;if(!active.add(node))throw new IllegalArgumentException("script "+script+" contains a "+kind+" cycle at "+node);for(String next:graph.getOrDefault(node,Set.of()))visit(next,graph,done,active,kind,script);active.remove(node);done.add(node);}
    private static Set<String> reachable(Map<String,Set<String>> graph,String root){Set<String> out=new HashSet<>();Deque<String> queue=new ArrayDeque<>();queue.add(root);while(!queue.isEmpty()){String node=queue.remove();if(out.add(node))queue.addAll(graph.getOrDefault(node,Set.of()));}return out;}
    private static boolean pathAvoiding(Map<String,Set<String>> graph,String root,String target,String avoided){if(root.equals(avoided))return false;Set<String> seen=new HashSet<>();Deque<String> queue=new ArrayDeque<>();queue.add(root);while(!queue.isEmpty()){String node=queue.remove();if(!seen.add(node))continue;if(node.equals(target))return true;for(String next:graph.getOrDefault(node,Set.of()))if(!next.equals(avoided))queue.add(next);}return false;}
    private static void rejectRecursion(String id,Map<String,ScriptDefinition> all,LinkedHashSet<String> path){if(!path.add(id))throw new IllegalArgumentException("recursive reusable script: "+String.join(" -> ",path)+" -> "+id);ScriptDefinition script=all.get(id);if(script!=null)for(Node node:script.nodes().values())if(node.type().equals("run-script"))rejectRecursion(String.valueOf(node.options().get("script")),all,new LinkedHashSet<>(path));}
    static void validateLiteral(ValueType type, Object value, String what) {
      boolean valid;
      if (type.equals(ValueType.BOOLEAN))
        valid = value instanceof Boolean;
      else if (type.equals(ValueType.INTEGER))
        valid = value instanceof Number n && n.doubleValue() == n.longValue();
      else if (type.equals(ValueType.NUMBER))
        valid = value instanceof Number;
      else if (type.equals(ValueType.DURATION)) {
        try {
          Duration.parse(String.valueOf(value));
          valid = true;
        } catch (Exception ignored) {
          valid = String.valueOf(value).matches("[0-9]+(?:ms|s|m|h|d)");
        }
      } else if(type.equals(ValueType.LOCATION))
        valid = value instanceof String || value instanceof Map<?,?> || value instanceof ConfigurationSection;
      else if(type.id().contains(":"))
        valid = value != null;
      else
        valid = value instanceof String || value instanceof ConfigurationSection;
      if (!valid)
        throw new IllegalArgumentException(what + " must be " + type.id());
    }
    private static void requireKey(String value,String what){if(value==null||!value.matches("[A-Za-z_][A-Za-z0-9_-]*"))throw new IllegalArgumentException("invalid "+what+" key "+value);}
    private static ConfigurationSection section(Object value,String what){if(value instanceof ConfigurationSection section)return section;throw new IllegalArgumentException(what+" must be a mapping");}
    private static Object plain(Object value){if(value instanceof ConfigurationSection section){Map<String,Object> out=new LinkedHashMap<>();section.getValues(false).forEach((key,item)->out.put(key,plain(item)));return Map.copyOf(out);}if(value instanceof List<?> list)return list.stream().map(ScriptDefinitionLoader::plain).toList();return value;}
    private static <T>T required(T value,String what){if(value==null||value instanceof String text&&text.isBlank())throw new IllegalArgumentException("missing "+what);return value;}
    private record Port(boolean output,boolean execution,ValueType type,boolean required){static Port exec(boolean output){return new Port(output,true,null,false);}static Port data(boolean output,ValueType type){return new Port(output,false,type,false);}static Port requiredData(boolean output,ValueType type){return new Port(output,false,type,true);}}
}
