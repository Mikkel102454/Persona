package nu.miguel.persona.behavior;

import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.content.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static nu.miguel.persona.behavior.BehaviorDefinition.BehaviorNode;

/** Strict stable-ID behavior loader. A candidate can be inspected without activation. */
public final class BehaviorLoader {
    private static final Set<String> COMPOSITES=Set.of("sequence","selector","priority-selector","parallel");
    private static final Set<String> DECORATORS=Set.of("invert","repeat","retry","timeout","cooldown","checkpoint");
    private static final Set<String> NATIVE_ACTIONS=Set.of("navigate","private-navigate","begin-private-presentation","wander","look","logical-travel","set-anchor","set-visible","remember","adjust-memory","forget","signal");
    private static final Set<String> CONDITIONS=Set.of("memory","event","quest-state","item-count","flag","variable","permission","world","chance");
    private static final Set<String> PLAYER_ACTIONS=Set.of("command","script","set-anchor","set-visible","private-navigate","begin-private-presentation");
    private static final Set<String> PLAYER_CONDITIONS=Set.of("quest-state","item-count","flag","variable","permission","world","chance");
    private static final Set<String> ROOT_KEYS=Set.of("content-version","id","scope","root");
    private static final Set<String> BASE=Set.of("id","type");
    private final File root; private final PersonaApi api; private final List<String> errors=new ArrayList<>();
    private final Map<String,String> behaviorSources=new LinkedHashMap<>(); private Validation.Source source;

    public record Candidate(Map<String,BehaviorDefinition> definitions,List<String> errors,Map<String,String> sources) {}
    public BehaviorLoader(File root,PersonaApi api){this.root=root;this.api=api;}

    public Map<String,BehaviorDefinition> load() throws ContentException {
        Candidate candidate=loadCandidate();if(!candidate.errors().isEmpty())throw new ContentException(candidate.errors());return candidate.definitions();
    }
    public Candidate loadCandidate() {
        errors.clear();behaviorSources.clear();File folder=new File(root,"behaviors");
        if(!folder.exists()&&!folder.mkdirs())errors.add("behaviors:1:1: cannot create directory");
        Map<String,BehaviorDefinition> result=new LinkedHashMap<>();File[] files=folder.listFiles(f->f.isFile()&&(f.getName().endsWith(".yml")||f.getName().endsWith(".yaml")));
        if(files==null)return new Candidate(Map.of(),List.copyOf(errors),Map.of());Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files){source=Validation.Source.read(root,file);try{
            YamlConfiguration yaml=new YamlConfiguration();yaml.load(file);Validation.keys(yaml,ROOT_KEYS);ContentFormat.validateBehavior(yaml);
            String id=id(yaml.getString("id"),"id");BehaviorScope scope=parseScope(yaml.getString("scope","player"));ConfigurationSection rootNode=yaml.getConfigurationSection("root");if(rootNode==null)throw new IllegalArgumentException("missing root node");
            LinkedHashMap<String,BehaviorNode> nodes=new LinkedHashMap<>();BehaviorNode parsed=node(rootNode,scope,nodes);BehaviorDefinition definition=new BehaviorDefinition(id,scope,parsed,nodes,hash(canonical(parsed)));
            BehaviorDefinition previous=result.putIfAbsent(id,definition);if(previous!=null){String first=behaviorSources.get(id);throw new IllegalArgumentException("conflicting behavior ID "+id+"; first declared at "+first);}
            behaviorSources.put(id,source.at("id","declaration").replace(": declaration",""));
        }catch(InvalidConfigurationException e){errors.add(source.at(null,"invalid YAML: "+Objects.toString(e.getMessage(),"parse failure")));}
        catch(IOException|RuntimeException e){errors.add(source.at(hint(e.getMessage()),Objects.toString(e.getMessage(),e.getClass().getSimpleName())));}}
        validateSubtrees(result);return new Candidate(Collections.unmodifiableMap(new LinkedHashMap<>(result)),List.copyOf(errors),Map.copyOf(behaviorSources));
    }

    private BehaviorNode node(ConfigurationSection s,BehaviorScope scope,Map<String,BehaviorNode> nodes){
        String id=id(s.getString("id"),"node id"),type=canonicalType(s.getString("type"));if(nodes.containsKey(id))throw new IllegalArgumentException("duplicate node ID "+id);nodes.put(id,null);
        Set<String> allowed=new LinkedHashSet<>(BASE);List<BehaviorNode> children=new ArrayList<>();BehaviorNode child=null;String subtree=null;
        if(COMPOSITES.contains(type)){allowed.add("children");if(type.equals("parallel"))allowed.addAll(Set.of("success-threshold","failure-threshold","cancel-remaining"));List<Map<?,?>> raw=s.getMapList("children");if(raw.isEmpty())throw new IllegalArgumentException(type+" "+id+" needs children");for(Map<?,?> value:raw)children.add(node(mapSection(value),scope,nodes));if(type.equals("parallel"))validateParallel(s,id,children.size());}
        else if(DECORATORS.contains(type)){allowed.add("child");if(Set.of("repeat","retry").contains(type)){allowed.addAll(Set.of("times","forever"));boolean forever=typedBoolean(s,"forever",false);if(forever&&s.contains("times"))throw new IllegalArgumentException(type+" "+id+" cannot set both forever and times");if(!forever&&typedInt(s,"times",1)<1)throw new IllegalArgumentException(type+" "+id+" times must be positive");}if(Set.of("timeout","cooldown").contains(type)){allowed.add("duration");duration(s,"duration",true);}Object raw=s.get("child");if(raw instanceof ConfigurationSection nested)child=node(nested,scope,nodes);else if(raw instanceof Map<?,?> map)child=node(mapSection(map),scope,nodes);else throw new IllegalArgumentException(type+" "+id+" needs child");}
        else if(type.equals("subtree")){allowed.add("behavior");subtree=id(s.getString("behavior"),"subtree behavior");}
        else if(type.equals("wait")){allowed.add("duration");duration(s,"duration",true);}
        else if(type.equals("condition")){String condition=canonicalType(s.getString("condition"));allowed.add("condition");if(!CONDITIONS.contains(condition)&&!extension(ExpansionTypes.BehaviorCondition.class,condition))throw new IllegalArgumentException("unknown behavior condition "+condition);if(scope==BehaviorScope.SHARED&&PLAYER_CONDITIONS.contains(condition))throw new IllegalArgumentException("player-only condition "+condition+" in shared tree");allowed.addAll(conditionKeys(condition));validateCondition(s,id,condition);if(scope==BehaviorScope.SHARED&&condition.equals("memory")&&s.getString("scope","global").equals("player"))throw new IllegalArgumentException("player memory condition in shared tree");if(condition.contains(":")){var handler=api.handler(ExpansionTypes.BehaviorCondition.class,condition).orElseThrow();validateCompatibility(scope,condition,handler.metadata());validateExtension(handler.metadata().schema(),s,allowed);}}
        else if(type.equals("action")){String action=canonicalType(s.getString("action"));allowed.add("action");if(!NATIVE_ACTIONS.contains(action)&&!Set.of("command","script").contains(action)&&!extension(ExpansionTypes.BehaviorAction.class,action))throw new IllegalArgumentException("unknown behavior action "+action);if(scope==BehaviorScope.SHARED&&PLAYER_ACTIONS.contains(action))throw new IllegalArgumentException("player-only action "+action+" in shared tree");allowed.addAll(actionKeys(action));if(action.equals("command")){String command=required(s.getString("command"),"command type");allowed.addAll(ContentLoader.commandOptionKeys(command));}validateAction(s,id,action);if(scope==BehaviorScope.SHARED&&Set.of("remember","adjust-memory","forget").contains(action)&&s.getString("scope","global").equals("player"))throw new IllegalArgumentException("player memory action "+action+" in shared tree");if(action.contains(":")){var handler=api.handler(ExpansionTypes.BehaviorAction.class,action).orElseThrow();validateCompatibility(scope,action,handler.metadata());validateExtension(handler.metadata().schema(),s,allowed);}}
        else throw new IllegalArgumentException("unknown node type "+type);
        Validation.keys(s,allowed);Map<String,Object> options=new LinkedHashMap<>();for(String key:s.getKeys(false))if(!Set.of("id","type","children","child","behavior").contains(key))options.put(key,s.get(key));
        if(type.equals("condition")){String custom=canonicalType(s.getString("condition"));if(custom.contains(":")){options=new LinkedHashMap<>(api.handler(ExpansionTypes.BehaviorCondition.class,custom).orElseThrow().parse(Map.copyOf(options)));options.put("condition",custom);}}
        if(type.equals("action")){String custom=canonicalType(s.getString("action"));if(custom.contains(":")){options=new LinkedHashMap<>(api.handler(ExpansionTypes.BehaviorAction.class,custom).orElseThrow().parse(Map.copyOf(options)));options.put("action",custom);}}
        BehaviorNode result=new BehaviorNode(id,type,children,child,subtree,options);nodes.put(id,result);return result;
    }

    private void validateCondition(ConfigurationSection s,String id,String type){switch(type){
        case "memory"->{required(s.getString("key"),"memory condition "+id+" key");enumText(s,"scope",Set.of("player","global","npc"),"player");enumText(s,"operator",Set.of("equals","not-equals","greater-than","greater-than-or-equal","less-than","less-than-or-equal","contains"),"equals");}
        case "event"->{required(s.getString("event",s.getString("name")),"event condition "+id+" event");typedBoolean(s,"consume",true);}
        case "quest-state"->{required(s.getString("quest"),"quest-state quest");enumText(s,"state",Set.of("not-started","active","completed"),null);}
        case "item-count"->{required(s.getString("material"),"item-count material");if(typedInt(s,"amount",1)<1)throw new IllegalArgumentException("item-count amount must be positive");}
        case "flag"->{required(s.getString("name"),"flag name");typedBoolean(s,"value",true);}
        case "variable"->{required(s.getString("name"),"variable name");enumText(s,"operator",Set.of("equals","not-equals","greater-than","greater-than-or-equal","less-than","less-than-or-equal","contains"),"equals");}
        case "permission"->required(s.getString("permission"),"permission");case "world"->required(s.getString("world"),"world");
        case "chance"->{double chance=typedDouble(s,"chance",Double.NaN);if(!Double.isFinite(chance)||chance<0||chance>1)throw new IllegalArgumentException("chance must be between 0 and 1");}default->{}}
    }
    private void validateAction(ConfigurationSection s,String id,String action){switch(action){
        case "navigate","private-navigate"->{required(s.getString("destination",s.getString("anchor")),action+" action "+id+" destination");validateNavigation(s,id);}
        case "look"->required(s.getString("anchor"),action+" action "+id+" anchor");case "set-anchor"->{}case "logical-travel"->{required(s.getString("destination",s.getString("anchor")),"logical-travel destination");duration(s,"duration",true);}
        case "wander"->{if(typedDouble(s,"radius",5)<=0)throw new IllegalArgumentException("wander radius must be positive");}
        case "set-visible"->typedBoolean(s,"visible",true);case "remember"->{required(s.getString("key"),"remember key");if(!s.contains("value"))throw new IllegalArgumentException("remember needs value");if(s.contains("value-type"))enumText(s,"value-type",Set.of("string","number","boolean"),null);optionalDuration(s,"ttl");memoryScope(s);}
        case "adjust-memory"->{required(s.getString("key"),"adjust-memory key");typedDouble(s,"amount",Double.NaN);optionalDuration(s,"ttl");memoryScope(s);}case "forget"->{required(s.getString("key"),"forget key");memoryScope(s);}
        case "signal"->required(s.getString("name"),"signal name");case "script"->required(s.getString("script"),"script name");case "command"->required(s.getString("command"),"command type");default->{}}
    }
    private static Set<String> conditionKeys(String type){return switch(type){case "memory"->Set.of("key","scope","operator","value");case "event"->Set.of("event","name","consume");case "quest-state"->Set.of("quest","state");case "item-count"->Set.of("material","amount");case "flag"->Set.of("name","value");case "variable"->Set.of("name","operator","value");case "permission"->Set.of("permission");case "world"->Set.of("world");case "chance"->Set.of("chance");default->Set.of();};}
    private static Set<String> actionKeys(String type){return switch(type){case "navigate","private-navigate"->Set.of("destination","anchor","arrival-distance","speed","pathfinding-range","stuck-seconds","stuck-action","stuck-retries");case "look","set-anchor"->Set.of("anchor");case "logical-travel"->Set.of("source","destination","anchor","duration");case "wander"->Set.of("radius");case "set-visible"->Set.of("visible");case "remember"->Set.of("key","value","value-type","ttl","scope");case "adjust-memory"->Set.of("key","amount","ttl","scope");case "forget"->Set.of("key","scope");case "signal"->Set.of("name");case "script"->Set.of("script");case "command"->Set.of("command");default->Set.of();};}

    private void validateParallel(ConfigurationSection s,String id,int count){int success=typedInt(s,"success-threshold",count),failure=typedInt(s,"failure-threshold",1);if(success<1||success>count||failure<1||failure>count)throw new IllegalArgumentException("parallel "+id+" has malformed thresholds");enumText(s,"cancel-remaining",Set.of("always","on-success","on-failure","never"),"always");}
    private void validateNavigation(ConfigurationSection s,String id){for(String key:Set.of("arrival-distance","speed","pathfinding-range","stuck-seconds"))if(s.contains(key)&&typedDouble(s,key,0)<=0)throw new IllegalArgumentException("navigation "+id+" "+key+" must be positive");if(s.contains("stuck-retries")&&typedInt(s,"stuck-retries",0)<0)throw new IllegalArgumentException("navigation "+id+" stuck-retries cannot be negative");enumText(s,"stuck-action",Set.of("fail","retry","teleport"),"fail");}
    private void validateSubtrees(Map<String,BehaviorDefinition> all){for(BehaviorDefinition d:all.values())for(BehaviorNode n:d.nodes().values())if(n.type().equals("subtree")){BehaviorDefinition target=all.get(n.subtree());if(target==null)errors.add(behaviorSources.get(d.id())+": missing subtree "+n.subtree());else if(target.scope()!=d.scope())errors.add(behaviorSources.get(d.id())+": subtree scope differs for "+n.subtree());}for(String id:all.keySet())visit(id,id,all,new LinkedHashSet<>());}
    private void visit(String origin,String current,Map<String,BehaviorDefinition> all,Set<String> path){if(!path.add(current)){errors.add(behaviorSources.get(origin)+": recursive subtree through "+current);return;}BehaviorDefinition d=all.get(current);if(d!=null)for(BehaviorNode n:d.nodes().values())if(n.subtree()!=null)visit(origin,n.subtree(),all,new LinkedHashSet<>(path));}
    private boolean extension(Class<?> type,String name){return name.contains(":")&&api!=null&&api.handler(type,name).isPresent();}
    private void validateExtension(Map<String,Object> schema,ConfigurationSection s,Set<String> allowed){if(schema==null||schema.isEmpty())return;Object properties=schema.get("properties");if(properties instanceof Map<?,?> p)for(Object key:p.keySet())allowed.add(String.valueOf(key));Map<String,Object> options=new LinkedHashMap<>();for(String key:s.getKeys(false))if(!BASE.contains(key)&&!Set.of("action","condition").contains(key))options.put(key,s.get(key));api.validateEditorData(()->schema,options,"extension node");}
    private static void validateCompatibility(BehaviorScope scope,String type,nu.miguel.persona.api.BehaviorNodeMetadata metadata){if(!metadata.scopes().contains(scope))throw new IllegalArgumentException("extension node "+type+" is not compatible with "+scope.name().toLowerCase(Locale.ROOT)+" scope");for(String field:metadata.durableFields().keySet())if(field==null||!field.matches("[a-z0-9][a-z0-9_.-]*"))throw new IllegalArgumentException("extension node "+type+" declares invalid durable field "+field);}
    private static ConfigurationSection mapSection(Map<?,?> map){YamlConfiguration y=new YamlConfiguration();for(var e:map.entrySet())y.set(String.valueOf(e.getKey()),e.getValue());return y;}
    private static BehaviorScope parseScope(String raw){try{return BehaviorScope.valueOf(raw.toUpperCase(Locale.ROOT).replace('-','_'));}catch(Exception e){throw new IllegalArgumentException("scope must be shared or player");}}
    private static String canonicalType(String raw){if(raw==null||raw.isBlank())throw new IllegalArgumentException("missing type");String v=raw.toLowerCase(Locale.ROOT).replace('_','-');if(!v.matches("[a-z0-9][a-z0-9:.-]*"))throw new IllegalArgumentException("invalid type "+raw);return v;}
    private static String id(String raw,String what){if(raw==null||!raw.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid "+what+" "+raw);return raw;}
    private static String required(String value,String what){if(value==null||value.isBlank())throw new IllegalArgumentException("missing "+what);return value;}
    private static int typedInt(ConfigurationSection s,String key,int fallback){if(!s.contains(key))return fallback;Object v=s.get(key);if(!(v instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException(key+" must be an integer");return n.intValue();}
    private static double typedDouble(ConfigurationSection s,String key,double fallback){if(!s.contains(key))return fallback;Object v=s.get(key);if(!(v instanceof Number n))throw new IllegalArgumentException(key+" must be a number");return n.doubleValue();}
    private static boolean typedBoolean(ConfigurationSection s,String key,boolean fallback){if(!s.contains(key))return fallback;if(!s.isBoolean(key))throw new IllegalArgumentException(key+" must be true or false");return s.getBoolean(key);}
    private static String enumText(ConfigurationSection s,String key,Set<String> values,String fallback){if(!s.contains(key)){if(fallback==null)throw new IllegalArgumentException("missing "+key);return fallback;}Object raw=s.get(key);if(!(raw instanceof String value)||!values.contains(value))throw new IllegalArgumentException(key+" must be one of "+String.join(", ",values));return value;}
    private static void duration(ConfigurationSection s,String key,boolean required){if(!s.contains(key)){if(required)throw new IllegalArgumentException("missing "+key);return;}try{if(Durations.parse(s.get(key)).isNegative())throw new IllegalArgumentException(key+" cannot be negative");}catch(RuntimeException e){throw new IllegalArgumentException("invalid "+key+": "+e.getMessage());}}
    private static void optionalDuration(ConfigurationSection s,String key){duration(s,key,false);}private static void memoryScope(ConfigurationSection s){enumText(s,"scope",Set.of("player","global","npc"),"player");}
    private static String hint(String message){if(message==null)return null;java.util.regex.Matcher m=java.util.regex.Pattern.compile("'(.*?)'").matcher(message);return m.find()?m.group(1):message.split(" ")[0];}
    private static String canonical(BehaviorNode n){return n.id()+"|"+n.type()+"|"+n.options()+"|"+n.subtree()+"|"+n.children().stream().map(BehaviorLoader::canonical).toList()+"|"+(n.child()==null?"":canonical(n.child()));}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
