package nu.miguel.persona.behavior;

import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.content.ContentException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static nu.miguel.persona.behavior.BehaviorDefinition.BehaviorNode;

/** Loads stable-ID behavior trees and rejects the complete candidate on any error. */
public final class BehaviorLoader {
    private static final Set<String> COMPOSITES=Set.of("sequence","selector","priority-selector","parallel");
    private static final Set<String> DECORATORS=Set.of("invert","repeat","retry","timeout","cooldown","checkpoint");
    private static final Set<String> LEAVES=Set.of("condition","action","wait","subtree");
    private static final Set<String> NATIVE_ACTIONS=Set.of("navigate","wander","look","logical-travel","set-anchor","set-visible","remember","adjust-memory","forget","signal");
    private static final Set<String> PLAYER_ACTIONS=Set.of("command","script","set-anchor","set-visible");
    private static final Set<String> PLAYER_CONDITIONS=Set.of("memory","event","quest-state","item-count","flag","variable","permission","world","chance");
    private final File root;
    private final PersonaApi api;
    private final List<String> errors=new ArrayList<>();

    public BehaviorLoader(File root,PersonaApi api){this.root=root;this.api=api;}

    public Map<String,BehaviorDefinition> load() throws ContentException {
        File folder=new File(root,"behaviors");
        if(!folder.exists()&&!folder.mkdirs())errors.add("behaviors: cannot create directory");
        Map<String,BehaviorDefinition> result=new LinkedHashMap<>();
        File[] files=folder.listFiles(f->f.isFile()&&(f.getName().endsWith(".yml")||f.getName().endsWith(".yaml")));
        if(files==null)return Map.of();
        Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files)try{
            YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);
            String id=id(yaml.getString("id"),"id");
            BehaviorScope scope=parseScope(yaml.getString("scope","player"));
            ConfigurationSection rootNode=yaml.getConfigurationSection("root");
            if(rootNode==null)throw new IllegalArgumentException("missing root node");
            LinkedHashMap<String,BehaviorNode> nodes=new LinkedHashMap<>();
            BehaviorNode parsed=node(rootNode,scope,nodes);
            String hash=hash(canonical(parsed));
            if(result.putIfAbsent(id,new BehaviorDefinition(id,scope,parsed,nodes,hash))!=null)throw new IllegalArgumentException("duplicate behavior ID "+id);
        }catch(RuntimeException e){errors.add("behaviors/"+file.getName()+": "+Objects.toString(e.getMessage(),e.getClass().getSimpleName()));}
        validateSubtrees(result);
        if(!errors.isEmpty())throw new ContentException(errors);
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private BehaviorNode node(ConfigurationSection section,BehaviorScope scope,Map<String,BehaviorNode> nodes){
        String id=id(section.getString("id"),"node id");
        String type=canonicalType(section.getString("type"));
        if(nodes.containsKey(id))throw new IllegalArgumentException("duplicate node ID "+id);
        nodes.put(id,null);
        List<BehaviorNode> children=new ArrayList<>();BehaviorNode child=null;String subtree=null;
        if(COMPOSITES.contains(type)){
            List<Map<?,?>> raw=section.getMapList("children");
            if(raw.isEmpty())throw new IllegalArgumentException(type+" "+id+" needs children");
            for(Map<?,?> value:raw)children.add(node(mapSection(value),scope,nodes));
            if(type.equals("parallel"))validateParallel(section,id,children.size());
        }else if(DECORATORS.contains(type)){
            Object value=section.get("child");if(value instanceof ConfigurationSection nested)child=node(nested,scope,nodes);else if(value instanceof Map<?,?> map)child=node(mapSection(map),scope,nodes);else throw new IllegalArgumentException(type+" "+id+" needs child");
            if(Set.of("repeat","retry").contains(type)&&section.getInt("times",1)<1)throw new IllegalArgumentException(type+" times must be positive");
            if(Set.of("timeout","cooldown").contains(type)&&!section.contains("duration"))throw new IllegalArgumentException(type+" needs duration");
        }else if(type.equals("subtree")){
            subtree=id(section.getString("behavior"),"subtree behavior");
        }else if(type.equals("wait")){
            if(!section.contains("duration"))throw new IllegalArgumentException("wait "+id+" needs duration");
        }else if(type.equals("condition")){
            String condition=canonicalType(section.getString("condition"));
            if(!PLAYER_CONDITIONS.contains(condition)&&!isExtension(ExpansionTypes.BehaviorCondition.class,condition))throw new IllegalArgumentException("unknown behavior condition "+condition);
            if(scope==BehaviorScope.SHARED&&!condition.equals("memory")&&!condition.equals("event")&&!isExtension(ExpansionTypes.BehaviorCondition.class,condition))throw new IllegalArgumentException("player-only condition "+condition+" in shared tree");
        }else if(type.equals("action")){
            String action=canonicalType(section.getString("action"));
            if(!NATIVE_ACTIONS.contains(action)&&!Set.of("command","script").contains(action)&&!isExtension(ExpansionTypes.BehaviorAction.class,action))throw new IllegalArgumentException("unknown behavior action "+action);
            if(scope==BehaviorScope.SHARED&&PLAYER_ACTIONS.contains(action))throw new IllegalArgumentException("player-only action "+action+" in shared tree");
            if(action.equals("command")&&section.getString("command")==null)throw new IllegalArgumentException("command action "+id+" needs command");
        }else throw new IllegalArgumentException("unknown node type "+type);
        Map<String,Object> options=new LinkedHashMap<>();for(String key:section.getKeys(false))if(!Set.of("id","type","children","child","behavior").contains(key))options.put(key,section.get(key));
        if(type.equals("condition")){String custom=canonicalType(section.getString("condition"));if(custom.contains(":")&&api!=null){options=new LinkedHashMap<>(api.handler(ExpansionTypes.BehaviorCondition.class,custom).orElseThrow().parse(Map.copyOf(options)));options.put("condition",custom);}}
        if(type.equals("action")){String custom=canonicalType(section.getString("action"));if(custom.contains(":")&&api!=null){options=new LinkedHashMap<>(api.handler(ExpansionTypes.BehaviorAction.class,custom).orElseThrow().parse(Map.copyOf(options)));options.put("action",custom);}}
        BehaviorNode result=new BehaviorNode(id,type,children,child,subtree,options);nodes.put(id,result);return result;
    }

    private void validateParallel(ConfigurationSection s,String id,int count){
        int success=s.getInt("success-threshold",count),failure=s.getInt("failure-threshold",1);
        if(success<1||success>count||failure<1||failure>count||success+failure<=count)throw new IllegalArgumentException("parallel "+id+" has malformed thresholds");
    }
    private void validateSubtrees(Map<String,BehaviorDefinition> all){
        for(BehaviorDefinition d:all.values())for(BehaviorNode n:d.nodes().values())if(n.type().equals("subtree")){
            BehaviorDefinition target=all.get(n.subtree());
            if(target==null)errors.add("behavior "+d.id()+": missing subtree "+n.subtree());
            else if(target.scope()!=d.scope())errors.add("behavior "+d.id()+": subtree scope differs for "+n.subtree());
        }
        for(String id:all.keySet())visit(id,id,all,new LinkedHashSet<>());
    }
    private void visit(String origin,String current,Map<String,BehaviorDefinition> all,Set<String> path){
        if(!path.add(current)){errors.add("behavior "+origin+": recursive subtree through "+current);return;}
        BehaviorDefinition d=all.get(current);if(d!=null)for(BehaviorNode n:d.nodes().values())if(n.subtree()!=null)visit(origin,n.subtree(),all,new LinkedHashSet<>(path));
    }
    private boolean isExtension(Class<?> type,String name){return name.contains(":")&&api!=null&&api.handler(type,name).isPresent();}
    private static ConfigurationSection mapSection(Map<?,?> map){YamlConfiguration y=new YamlConfiguration();for(var e:map.entrySet())y.set(String.valueOf(e.getKey()),e.getValue());return y;}
    private static BehaviorScope parseScope(String raw){try{return BehaviorScope.valueOf(raw.toUpperCase(Locale.ROOT).replace('-','_'));}catch(Exception e){throw new IllegalArgumentException("scope must be shared or player");}}
    private static String canonicalType(String raw){if(raw==null||raw.isBlank())throw new IllegalArgumentException("missing type");String v=raw.toLowerCase(Locale.ROOT).replace('_','-');if(!v.matches("[a-z0-9][a-z0-9:.-]*"))throw new IllegalArgumentException("invalid type "+raw);return v;}
    private static String id(String raw,String what){if(raw==null||!raw.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid "+what+" "+raw);return raw;}
    private static String canonical(BehaviorNode n){return n.id()+"|"+n.type()+"|"+n.options()+"|"+n.subtree()+"|"+n.children().stream().map(BehaviorLoader::canonical).toList()+"|"+(n.child()==null?"":canonical(n.child()));}
    private static String hash(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(bytes);}catch(Exception e){throw new IllegalStateException(e);}}
}
