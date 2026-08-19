package nu.miguel.persona.content;

import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.script.ScriptDefinition;
import nu.miguel.persona.script.ScriptDefinition.*;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.*;

/** Parser and structural/type validator for the content-version 2 graph language. */
final class ScriptDefinitionLoader {
    private final PersonaApi api;
    ScriptDefinitionLoader(PersonaApi api){this.api=api;}
    private static final Set<String> REUSABLE_KEYS=Set.of("content-version","id","inputs","outputs","variables","nodes","connections","tags");
    private static final Set<String> EVENT_KEYS=Set.of("variables","nodes","connections");
    private static final Map<String,ValueType> COMMAND_INPUTS=Map.ofEntries(
            Map.entry("quest",ValueType.QUEST), Map.entry("objective",ValueType.QUEST_OBJECTIVE),
            Map.entry("player",ValueType.PLAYER),
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
    private static final Set<String> PLAYER_TARGET_COMMANDS=Set.of("start-quest","finish-quest","deliver-items",
            "set-flag","set-variable","message","action-bar","title","play-sound","particle","give-item",
            "take-item","give-experience","run-command","teleport","potion-effect","npc-speak");

    /** Retained only to produce an actionable error when an old monolithic file is encountered. */
    Map<String,ScriptDefinition> parse(ConfigurationSection scripts) {
        throw new IllegalArgumentException("monolithic scripts.yml is obsolete; content-version 2 requires one reusable script per scripts/<folders>/<file>.yml");
    }

    ScriptDefinition parseReusable(ConfigurationSection descriptor) {
        Validation.keys(descriptor,REUSABLE_KEYS);
        requireVersion2(descriptor,"reusable script");
        String id=Objects.toString(descriptor.getString("id"),"");
        if(!id.matches("[a-z0-9][a-z0-9_.:-]{0,127}"))throw new IllegalArgumentException("reusable script id must use lowercase letters, digits, dot, underscore, hyphen, or colon");
        return new ScriptDefinition(id,ScriptDefinition.Boundary.REUSABLE,
                parameters(requiredSection(descriptor,"inputs"),"input"),
                parameters(requiredSection(descriptor,"outputs"),"output"),
                variables(requiredSection(descriptor,"variables")),
                nodes(requiredSection(descriptor,"nodes")),
                connections(requiredSection(descriptor,"connections")));
    }

    ScriptDefinition parseEvent(Object raw,String id,Map<String,Parameter> eventOutputs) {
        ConfigurationSection descriptor=section(raw,id);
        Validation.keys(descriptor,EVENT_KEYS);
        ScriptDefinition graph=ScriptDefinition.event(id,eventOutputs,
                variables(requiredSection(descriptor,"variables")),
                nodes(requiredSection(descriptor,"nodes")),
                connections(requiredSection(descriptor,"connections")));
        return graph;
    }

    void validateAll(Collection<ScriptDefinition> graphs,Map<String,ScriptDefinition> reusable) {
        graphs.forEach(definition->validate(definition,reusable));
        reusable.keySet().forEach(id->rejectRecursion(id,reusable,new LinkedHashSet<>()));
    }

    @Deprecated
    Map<String,ScriptDefinition> parseLegacyForTests(ConfigurationSection scripts) {
        Map<String,ScriptDefinition> result=new LinkedHashMap<>();
        for(String id:scripts.getKeys(false)) {
            Object raw=scripts.get(id);
            if(raw instanceof List<?>) throw new IllegalArgumentException("script "+id+" uses obsolete list form; scripts.yml content-version 2 requires inputs, outputs, nodes, and connections (see SCRIPT_FORMAT_2_MIGRATION.md)");
            ConfigurationSection descriptor=section(raw,"script "+id);
            Validation.keys(descriptor,Set.of("inputs","outputs","variables","nodes","connections"));
            result.put(id,new ScriptDefinition(id,ScriptDefinition.Boundary.REUSABLE,
                    parameters(requiredSection(descriptor,"inputs"),"input"),
                    parameters(requiredSection(descriptor,"outputs"),"output"),
                    variables(requiredSection(descriptor,"variables")),
                    nodes(requiredSection(descriptor,"nodes")),connections(requiredSection(descriptor,"connections"))));
        }
        result.values().forEach(definition->validate(definition,result));
        result.keySet().forEach(id->rejectRecursion(id,result,new LinkedHashSet<>()));
        return Collections.unmodifiableMap(result);
    }

    private Map<String,Variable> variables(ConfigurationSection section) {
        Map<String,Variable> out=new LinkedHashMap<>();
        for(String name:section.getKeys(false)) {
            requireKey(name,"variable");ConfigurationSection value=section(section.get(name),"variable "+name);
            Validation.keys(value,Set.of("type","default"));
            ValueType type=valueType(required(value.get("type"),"variable "+name+" type"),"variable "+name+" type");
            Object defaultValue=plain(value.get("default"));
            if(defaultValue!=null)validateLiteral(type,defaultValue,"variable "+name+" default");
            out.put(name,new Variable(type,defaultValue));
        }
        return out;
    }

    Map<String,Parameter> parameters(ConfigurationSection section,String kind) {
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
            requireKey(id,"script node");if(Set.of(ScriptDefinition.INPUT,ScriptDefinition.OUTPUT,ScriptDefinition.EVENT).contains(id))throw new IllegalArgumentException(id+" is reserved for graph boundaries");
            ConfigurationSection value=section(section.get(id),"node "+id);String type=Objects.toString(required(value.get("type"),"node "+id+" type"));
            Map<String,Object> options=new LinkedHashMap<>();value.getValues(false).forEach((key,item)->{if(!key.equals("type"))options.put(key,plain(item));});
            if(!type.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("node "+id+" type must be lowercase kebab-case");
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
            if(!from.execution&&!from.type.equals(to.type))throw new IllegalArgumentException("script "+script.id()+" connection "+id+" requires exact type "+to.type.id()+", got "+from.type.id());
            if(incoming.putIfAbsent(connection.to(),id)!=null)throw new IllegalArgumentException("script "+script.id()+" input "+connection.to()+" has more than one connection");
            if(from.execution&&executionOutgoing.putIfAbsent(connection.from(),id)!=null)throw new IllegalArgumentException("script "+script.id()+" execution output "+connection.from()+" has more than one connection");
            (from.execution?execGraph:dataGraph).computeIfAbsent(connection.from().node(),ignored->new LinkedHashSet<>()).add(connection.to().node());
        }
        rejectCycles(dataGraph,"data",script.id());rejectCycles(execGraph,"execution",script.id());
        Endpoint entryExec=new Endpoint(script.entryBoundary(),"exec");
        if(!executionOutgoing.containsKey(entryExec))throw new IllegalArgumentException("graph "+script.id()+" has no connection from "+entryExec);
        Set<String> execReachable=reachable(execGraph,script.entryBoundary());
        if(script.boundary()==ScriptDefinition.Boundary.REUSABLE){
            if(!incoming.containsKey(new Endpoint(ScriptDefinition.OUTPUT,"exec")))throw new IllegalArgumentException("script "+script.id()+" has no execution path to $output.exec");
            if(!execReachable.contains(ScriptDefinition.OUTPUT))throw new IllegalArgumentException("script "+script.id()+" $output.exec is unreachable");
            for(var output:script.outputs().entrySet())if(output.getValue().required()&&!incoming.containsKey(new Endpoint(ScriptDefinition.OUTPUT,output.getKey())))
                throw new IllegalArgumentException("script "+script.id()+" required output "+output.getKey()+" is unwired");
        }
        for(var entry:ports.entrySet())if(entry.getValue().required&&!entry.getValue().output&&!entry.getValue().execution&&!incoming.containsKey(entry.getKey())){Node owner=script.nodes().get(entry.getKey().node());if(owner==null||!inline(owner,entry.getKey().pin()))throw new IllegalArgumentException("script "+script.id()+" required input "+entry.getKey()+" is unwired and has no inline default");}
        for(Connection connection:script.connections().values())if(!ports.get(connection.from()).execution&&!pure(script.nodes().get(connection.from().node()))){String producer=connection.from().node(),consumer=connection.to().node();if(producer.equals(consumer)||!execReachable.contains(producer)||pathAvoiding(execGraph,script.entryBoundary(),consumer,producer))throw new IllegalArgumentException("script "+script.id()+" reads impure output "+connection.from()+" before execution");}
    }

    private Map<Endpoint,Port> ports(ScriptDefinition script,Map<String,ScriptDefinition> all) {
        Map<Endpoint,Port> out=new HashMap<>();String entry=script.entryBoundary();out.put(new Endpoint(entry,"exec"),Port.exec(true));
        script.inputs().forEach((name,p)->out.put(new Endpoint(entry,name),Port.data(true,p.type())));
        if(script.boundary()==ScriptDefinition.Boundary.REUSABLE){out.put(new Endpoint(ScriptDefinition.OUTPUT,"exec"),Port.exec(false));script.outputs().forEach((name,p)->out.put(new Endpoint(ScriptDefinition.OUTPUT,name),Port.data(false,p.type())));}
        script.nodes().forEach((id,node)->nodePorts(id,node,script,all).forEach((pin,port)->out.put(new Endpoint(id,pin),port)));
        return out;
    }

    private Map<String,Port> nodePorts(String id,Node node,ScriptDefinition owner,Map<String,ScriptDefinition> all) {
        Map<String,Port> out=new HashMap<>();String type=node.type();
        if(type.equals("value")){out.put("value",Port.data(true,valueType(node.options().get("value-type"),"node "+id+" value-type")));return out;}
        if(type.equals("get-variable")){Variable variable=variable(owner,id,node);out.put("value",Port.data(true,variable.type()));return out;}
        if(type.equals("integer-to-number")){out.put("value",Port.requiredData(false,ValueType.INTEGER));out.put("result",Port.data(true,ValueType.NUMBER));return out;}
        if(type.equals("string-to-text")){out.put("value",Port.requiredData(false,ValueType.STRING));out.put("result",Port.data(true,ValueType.TEXT));return out;}
        if(type.equals("to-string")){ValueType source=valueType(required(node.options().get("value-type"),"node "+id+" value-type"),"node "+id+" value-type");if(!source.domainId()&&!source.id().contains(":")&&source!=ValueType.BOOLEAN&&source!=ValueType.INTEGER&&source!=ValueType.NUMBER)throw new IllegalArgumentException("node "+id+" to-string does not allow "+source.id());out.put("value",Port.requiredData(false,source));out.put("result",Port.data(true,ValueType.STRING));return out;}
        if(Set.of("equals","not-equals","greater-than","greater-than-or-equal","less-than","less-than-or-equal").contains(type)){
            ValueType operand=valueType(required(node.options().get("value-type"),"node "+id+" value-type"),"node "+id+" value-type");
            if(!Set.of("equals","not-equals").contains(type)&&!Set.of(ValueType.INTEGER,ValueType.NUMBER,ValueType.DURATION,ValueType.STRING,ValueType.TEXT).contains(operand))throw new IllegalArgumentException("node "+id+" "+type+" requires ordered operands");
            out.put("left",Port.requiredData(false,operand));out.put("right",Port.requiredData(false,operand));out.put("result",Port.data(true,ValueType.BOOLEAN));return out;
        }
        if(Set.of("and","or").contains(type)){out.put("left",Port.requiredData(false,ValueType.BOOLEAN));out.put("right",Port.requiredData(false,ValueType.BOOLEAN));out.put("result",Port.data(true,ValueType.BOOLEAN));return out;}
        if(type.equals("not")){out.put("value",Port.requiredData(false,ValueType.BOOLEAN));out.put("result",Port.data(true,ValueType.BOOLEAN));return out;}
        if(type.startsWith("get-player-")||type.startsWith("get-global-npc-memory")||type.startsWith("get-player-npc-memory")){
            ValueType valueType=memoryType(id,node,type);out.put("value",Port.data(true,valueType));return out;
        }
        out.put("exec",Port.exec(false));
        if(type.equals("choice")){List<?> options=listOption(node,"options",id);if(options.isEmpty()||options.size()>32)throw new IllegalArgumentException("node "+id+" choice needs 1-32 options");for(int index=0;index<options.size();index++){if(!(options.get(index) instanceof Map<?,?> option)||Objects.toString(option.get("text"),"").isBlank())throw new IllegalArgumentException("node "+id+" choice option "+index+" needs text");out.put("option-"+index,Port.exec(true));}return out;}
        if(Set.of("branch","if").contains(type)){out.put("true",Port.exec(true));out.put("false",Port.exec(true));out.put("condition",Port.requiredData(false,ValueType.BOOLEAN));return out;}
        if(type.equals("sequence")){int count=boundedCount(node,"count",2,2,64);for(int index=0;index<count;index++)out.put("then-"+index,Port.exec(true));out.put("completed",Port.exec(true));return out;}
        if(type.equals("switch")){ValueType valueType=valueType(required(node.options().get("value-type"),"node "+id+" value-type"),"node "+id+" value-type");out.put("value",Port.requiredData(false,valueType));List<?> cases=listOption(node,"cases",id);if(cases.isEmpty()||cases.size()>64)throw new IllegalArgumentException("node "+id+" switch needs 1-64 cases");Set<String> names=new HashSet<>();for(Object value:cases){validateLiteral(valueType,value,"node "+id+" case");String pin="case-"+pinName(value);if(!names.add(pin))throw new IllegalArgumentException("node "+id+" has duplicate switch case "+value);out.put(pin,Port.exec(true));}out.put("default",Port.exec(true));return out;}
        if(type.equals("random")){List<?> weights=listOption(node,"weights",id);if(weights.isEmpty()||weights.size()>64)throw new IllegalArgumentException("node "+id+" random needs 1-64 weights");for(int index=0;index<weights.size();index++){Object weight=weights.get(index);if(!(weight instanceof Number n)||n.doubleValue()<=0||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("node "+id+" random weights must be positive numbers");out.put("option-"+index,Port.exec(true));}return out;}
        if(type.equals("gate")){for(String pin:Set.of("enter","open","close","toggle"))out.put(pin,Port.exec(false));out.put("exit",Port.exec(true));out.put("closed",Port.exec(true));return out;}
        if(type.equals("do-once")){out.put("reset",Port.exec(false));out.put("completed",Port.exec(true));out.put("skipped",Port.exec(true));return out;}
        if(type.equals("do-n")){out.put("reset",Port.exec(false));out.put("n",Port.requiredData(false,ValueType.INTEGER));out.put("completed",Port.exec(true));out.put("exhausted",Port.exec(true));out.put("count",Port.data(true,ValueType.INTEGER));return out;}
        if(type.equals("for")){out.put("first",Port.requiredData(false,ValueType.INTEGER));out.put("last",Port.requiredData(false,ValueType.INTEGER));out.put("step",Port.data(false,ValueType.INTEGER));out.put("body",Port.exec(true));out.put("completed",Port.exec(true));out.put("index",Port.data(true,ValueType.INTEGER));return out;}
        if(type.equals("for-each")){ValueType element=valueType(required(node.options().get("element-type"),"node "+id+" element-type"),"node "+id+" element-type");out.put("items",Port.requiredData(false,ValueType.parse("list:"+element.id())));out.put("body",Port.exec(true));out.put("completed",Port.exec(true));out.put("item",Port.data(true,element));out.put("index",Port.data(true,ValueType.INTEGER));return out;}
        if(type.equals("while")){out.put("condition",Port.requiredData(false,ValueType.BOOLEAN));out.put("body",Port.exec(true));out.put("completed",Port.exec(true));return out;}
        if(Set.of("stop","goto","end-dialogue").contains(type))return out;
        out.put("success",Port.exec(true));out.put("failure",Port.exec(true));
        if(type.equals("set-variable")){Variable variable=variable(owner,id,node);out.put("value",Port.requiredData(false,variable.type()));out.put("result",Port.data(true,variable.type()));}
        else if(type.equals("set-player-flag")){out.put("name",Port.requiredData(false,ValueType.STRING));out.put("value",Port.requiredData(false,ValueType.BOOLEAN));}
        else if(type.equals("set-player-string")){out.put("name",Port.requiredData(false,ValueType.STRING));out.put("value",Port.requiredData(false,ValueType.STRING));}
        else if(type.startsWith("set-global-npc-memory")||type.startsWith("set-player-npc-memory")){ValueType valueType=memoryType(id,node,type);out.put("key",Port.requiredData(false,ValueType.STRING));out.put("value",Port.requiredData(false,valueType));}
        if(type.equals("run-script")){String target=Objects.toString(required(node.options().get("script"),"node "+id+" script"));ScriptDefinition call=all.get(target);if(call==null)throw new IllegalArgumentException("node "+id+" references missing script "+target);Object rawInputs=required(node.options().get("inputs"),"node "+id+" inputs mapping");if(!(rawInputs instanceof Map<?,?> inputs))throw new IllegalArgumentException("node "+id+" inputs must be a mapping");for(var input:inputs.entrySet()){String name=String.valueOf(input.getKey());Parameter parameter=call.inputs().get(name);if(parameter==null)throw new IllegalArgumentException("node "+id+" has unknown input "+name);validateLiteral(parameter.type(),input.getValue(),"node "+id+" input "+name);}call.inputs().forEach((name,p)->out.put(name,p.required()?Port.requiredData(false,p.type()):Port.data(false,p.type())));call.outputs().forEach((name,p)->out.put(name,Port.data(true,p.type())));}
        else if(type.equals("wait"))out.put("duration",Port.requiredData(false,ValueType.DURATION));
        else if(type.equals("say")){out.put("text",Port.data(false,ValueType.TEXT));out.put("delay",Port.data(false,ValueType.DURATION));}
        else if(type.contains(":")){if(api==null)throw new IllegalArgumentException("node "+id+" references unavailable extension command "+type);ExpansionTypes.Command command=api.handler(ExpansionTypes.Command.class,PersonaApi.canonical(type)).orElseThrow(()->new IllegalArgumentException("node "+id+" references unavailable extension command "+type));command.inputPins().forEach(pin->{ValueType pinType=valueType(pin.valueType(),"extension input "+pin.name());out.put(pin.name(),pin.required()?Port.requiredData(false,pinType):Port.data(false,pinType));});command.outputPins().forEach(pin->out.put(pin.name(),Port.data(true,valueType(pin.valueType(),"extension output "+pin.name()))));}
        else {
            ContentLoader.commandOptionKeys(type).forEach(name->{ValueType pinType=commandInputType(type,name);out.put(name,requiredCommandPin(type,name)?Port.requiredData(false,pinType):Port.data(false,pinType));});
            if(PLAYER_TARGET_COMMANDS.contains(type))out.put("player",Port.requiredData(false,ValueType.PLAYER));
        }
        return out;
    }
    private static boolean pure(Node node){return node==null||Set.of("value","get-variable","integer-to-number","string-to-text","to-string",
            "equals","not-equals","greater-than","greater-than-or-equal","less-than","less-than-or-equal","and","or","not",
            "get-player-flag","get-player-string","get-global-npc-memory","get-player-npc-memory").contains(node.type());}
    private ValueType valueType(Object raw,String what){ValueType type=ValueType.parse(raw);ValueType declared=type.list()?type.elementType():type;if(declared.id().contains(":")&&(api==null||!api.scriptValueTypes().contains(declared.id())))throw new IllegalArgumentException(what+" uses undeclared extension value type "+declared.id());return type;}
    private static Variable variable(ScriptDefinition owner,String id,Node node){String name=Objects.toString(required(node.options().get("variable"),"node "+id+" variable"));Variable variable=owner.variables().get(name);if(variable==null)throw new IllegalArgumentException("node "+id+" references undeclared variable "+name);return variable;}
    private ValueType memoryType(String id,Node node,String type){Object raw=node.options().get("value-type");if(type.endsWith("flag"))return ValueType.BOOLEAN;if(type.endsWith("string"))return ValueType.STRING;return valueType(required(raw,"node "+id+" value-type"),"node "+id+" value-type");}
    private static int boundedCount(Node node,String key,int fallback,int minimum,int maximum){Object raw=node.options().get(key);int value=raw==null?fallback:raw instanceof Number n&&n.doubleValue()==n.intValue()?n.intValue():-1;if(value<minimum||value>maximum)throw new IllegalArgumentException("node count must be between "+minimum+" and "+maximum);return value;}
    private static List<?> listOption(Node node,String key,String id){Object raw=node.options().get(key);if(!(raw instanceof List<?> values))throw new IllegalArgumentException("node "+id+" "+key+" must be a list");return values;}
    private static String pinName(Object value){String pin=String.valueOf(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+","-").replaceAll("^-+|-+$","");if(pin.isBlank())throw new IllegalArgumentException("switch case cannot form a pin label");return pin;}
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
      if(type.list()){
        if(!(value instanceof List<?> values))throw new IllegalArgumentException(what+" must be "+type.id());
        for(Object element:values)validateLiteral(type.elementType(),element,what+" element");
        return;
      }
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
      } else if(type.equals(ValueType.PLAYER)||type.equals(ValueType.NPC_INSTANCE))
        valid = false;
      else if(type.equals(ValueType.CONDITION)||type.equals(ValueType.DIALOGUE_REGISTRATION))
        valid = value instanceof Map<?,?> || value instanceof ConfigurationSection;
      else if(type.equals(ValueType.LOCATION))
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
    private static ConfigurationSection requiredSection(ConfigurationSection owner,String key){ConfigurationSection section=owner.getConfigurationSection(key);if(section==null)throw new IllegalArgumentException("missing "+key+" mapping");return section;}
    private static void requireVersion2(ConfigurationSection descriptor,String what){Object raw=descriptor.get("content-version");if(!(raw instanceof Number number)||number.doubleValue()!=2.0)throw new IllegalArgumentException(what+" requires content-version: 2");}
    private static Object plain(Object value){if(value instanceof ConfigurationSection section){Map<String,Object> out=new LinkedHashMap<>();section.getValues(false).forEach((key,item)->out.put(key,plain(item)));return Map.copyOf(out);}if(value instanceof List<?> list)return list.stream().map(ScriptDefinitionLoader::plain).toList();return value;}
    private static <T>T required(T value,String what){if(value==null||value instanceof String text&&text.isBlank())throw new IllegalArgumentException("missing "+what);return value;}
    private record Port(boolean output,boolean execution,ValueType type,boolean required){static Port exec(boolean output){return new Port(output,true,null,false);}static Port data(boolean output,ValueType type){return new Port(output,false,type,false);}static Port requiredData(boolean output,ValueType type){return new Port(output,false,type,true);}}
}
