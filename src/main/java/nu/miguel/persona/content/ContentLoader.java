package nu.miguel.persona.content;

import nu.miguel.persona.api.*;
import nu.miguel.persona.behavior.BehaviorDefinition;
import nu.miguel.persona.behavior.BehaviorLoader;
import nu.miguel.persona.behavior.BehaviorScope;
import nu.miguel.persona.script.ScriptDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import static nu.miguel.persona.content.Content.*;

/** Strict Persona 2.0 loader. Legacy executable shapes are deliberately rejected. */
public final class ContentLoader {
    private static final String MIGRATION="; Persona 2.0 requires ordered typed scripts (see AUTHORING.md migration guide)";
    private static final Set<String> BUILTIN_COMMANDS=Set.of("start-quest","finish-quest","deliver-items","set-flag","set-variable","message","action-bar","title","play-sound","particle","give-item","take-item","give-experience","run-command","teleport","lightning-effect","potion-effect","broadcast","spawn-entity","set-block","npc-animation","npc-speak","npc-move");
    private final File root; private final Duration defaultDelay; private final Function<String,Material> materials;
    private final Function<String,EntityType> entities; private final PersonaApi api; private final List<String> errors=new ArrayList<>();
    private final Map<Object,String> sources=new IdentityHashMap<>(); private String source; private Validation.Source document;
    private Map<String,String> behaviorSources=Map.of();

    public ContentLoader(File root,Duration defaultDelay){this(root,defaultDelay,ContentLoader::bukkitMaterial,ContentLoader::bukkitEntity,null);}
    public ContentLoader(File root,Duration defaultDelay,PersonaApi api){this(root,defaultDelay,ContentLoader::bukkitMaterial,ContentLoader::bukkitEntity,api);}
    ContentLoader(File root,Duration delay,Function<String,Material> materials,Function<String,EntityType> entities){this(root,delay,materials,entities,null);}
    ContentLoader(File root,Duration delay,Function<String,Material> materials,Function<String,EntityType> entities,PersonaApi api){this.root=root;defaultDelay=delay;this.materials=materials;this.entities=entities;this.api=api;}

    public Content.Registry load() throws ContentException {
        errors.clear();sources.clear();
        if(new File(root,"effects.yml").isFile())errors.add("effects.yml:1:1: obsolete effects.yml"+MIGRATION);
        Map<String,ScriptDefinition> scripts=loadScripts();
        BehaviorLoader.Candidate behaviorCandidate=new BehaviorLoader(root,api).loadCandidate();errors.addAll(behaviorCandidate.errors());behaviorSources=behaviorCandidate.sources();Map<String,BehaviorDefinition> behaviors=behaviorCandidate.definitions();
        Map<String,Npc> npcs=loadDirectory("npcs",this::npc);Map<String,Dialogue> dialogues=loadDirectory("dialogues",this::dialogue);Map<String,Quest> quests=loadDirectory("quests",this::quest);
        validate(npcs,dialogues,quests,scripts,behaviors);if(!errors.isEmpty())throw new ContentException(errors);
        return new Content.Registry(Map.copyOf(npcs),Map.copyOf(dialogues),Map.copyOf(quests),Map.copyOf(scripts),behaviors);
    }

    private Map<String,ScriptDefinition> loadScripts(){File file=new File(root,"scripts.yml");if(!file.isFile())return Map.of();source="scripts.yml";document=Validation.Source.read(root,file);try{YamlConfiguration y=yaml(file);Validation.keys(y,Set.of("content-version","scripts"));Object version=y.get("content-version");if(!(version instanceof Number n)||n.intValue()!=2||n.doubleValue()!=2)throw new IllegalArgumentException("scripts.yml requires content-version: 2; list-form/format-1 reusable scripts require manual migration (see SCRIPT_FORMAT_2_MIGRATION.md)");ConfigurationSection s=y.getConfigurationSection("scripts");if(s==null){error("missing scripts section");return Map.of();}return new ScriptDefinitionLoader(api).parse(s);}catch(Exception e){error(e);return Map.of();}}
    private <T> Map<String,T> loadDirectory(String dir,Function<ConfigurationSection,T> parser){File folder=new File(root,dir);if(!folder.exists()&&!folder.mkdirs())errors.add(dir+":1:1: cannot create directory");Map<String,T> out=new LinkedHashMap<>();Map<String,String> declarations=new HashMap<>();File[] files=folder.listFiles(f->f.isFile()&&(f.getName().endsWith(".yml")||f.getName().endsWith(".yaml")));if(files==null)return out;Arrays.sort(files,Comparator.comparing(File::getName));for(File f:files){source=dir+"/"+f.getName();document=Validation.Source.read(root,f);try{YamlConfiguration yaml=yaml(f);ContentFormat.validate(yaml,document);T value=parser.apply(yaml);sources.put(value,source);String id=value instanceof Npc n?n.id():value instanceof Dialogue d?d.id():((Quest)value).id();if(out.putIfAbsent(id,value)!=null)throw new IllegalArgumentException("conflicting "+dir.substring(0,dir.length()-1)+" ID "+id+"; first declared at "+declarations.get(id));declarations.put(id,document.at("id","declaration").replace(": declaration",""));}catch(Exception e){error(e);}}return out;}

    private static YamlConfiguration yaml(File file)throws Exception{YamlConfiguration yaml=new YamlConfiguration();yaml.load(file);return yaml;}

    private Npc npc(ConfigurationSection s){reject(s,"actions","effects","on-interact-effects");Validation.keys(s,Set.of("content-version","id","display-name","dialogues","on-interact","on-no-dialogue","anchors","shared-behavior","player-behavior"));String id=id(s.getString("id"),"id");List<DialogueRegistration> ds=new ArrayList<>();for(Map<?,?> m:maps(s,"dialogues")){reject(m,"conditions");Validation.keys(m,Set.of("id","priority","when"));ds.add(new DialogueRegistration(id(str(m,"id"),"dialogue id"),integer(m,"priority",0),m.containsKey("when")?condition(m.get("when")):new All(List.of())));}ds.sort(Comparator.comparingInt(DialogueRegistration::priority).reversed());Map<String,Anchor> anchors=new LinkedHashMap<>();ConfigurationSection as=s.getConfigurationSection("anchors");if(as!=null)for(String name:as.getKeys(false)){ConfigurationSection a=required(as.getConfigurationSection(name),"anchor "+name);Validation.keys(a,Set.of("world","x","y","z","yaw","pitch"));for(String coordinate:Set.of("x","y","z"))if(!a.contains(coordinate)||!(a.get(coordinate) instanceof Number))throw new IllegalArgumentException("anchor "+name+" "+coordinate+" must be a number");String aid=anchorId(name);anchors.put(aid,new Anchor(required(a.getString("world"),"anchor world"),a.getDouble("x"),a.getDouble("y"),a.getDouble("z"),(float)a.getDouble("yaw",0),(float)a.getDouble("pitch",0)));}return new Npc(id,s.getString("display-name",id),List.copyOf(ds),steps(s.get("on-interact"),"on-interact"),steps(s.get("on-no-dialogue"),"on-no-dialogue"),Map.copyOf(anchors),s.getString("shared-behavior"),s.getString("player-behavior"));}
    private Dialogue dialogue(ConfigurationSection s){Validation.keys(s,Set.of("content-version","id","start","nodes"));String id=id(s.getString("id"),"id"),start=required(s.getString("start"),"start");ConfigurationSection ns=required(s.getConfigurationSection("nodes"),"nodes");Map<String,Node> nodes=new LinkedHashMap<>();for(String nodeId:ns.getKeys(false)){ConfigurationSection n=required(ns.getConfigurationSection(nodeId),"node "+nodeId);reject(n,"lines","choices","on-enter","on-exit");Validation.keys(n,Set.of("script"));nodes.put(nodeId,new Node(nodeId,steps(n.get("script"),"node script")));}return new Dialogue(id,start,Map.copyOf(nodes));}
    private Quest quest(ConfigurationSection s){reject(s,"rewards");Validation.keys(s,Set.of("content-version","id","title","description","phases","when","requirements","repeatable","cooldown","maximum-completions","time-limit","on-start","on-complete","on-fail","on-reset"));String id=id(s.getString("id"),"id");List<Phase> phases=new ArrayList<>();for(Map<?,?> p:maps(s,"phases")){reject(p,"rewards");Validation.keys(p,Set.of("id","title","description","objectives","branches","on-start","on-complete"));List<Objective> objectives=new ArrayList<>();if(p.get("objectives") instanceof List<?> list)for(Object raw:list)objectives.add(objective(asMap(raw)));List<PhaseBranch> branches=new ArrayList<>();if(p.get("branches") instanceof List<?> list)for(Object raw:list){Map<?,?> b=asMap(raw);reject(b,"conditions");Validation.keys(b,Set.of("when","next-phase"));branches.add(new PhaseBranch(condition(required(b.get("when"),"branch when")),required(str(b,"next-phase"),"next-phase").toLowerCase(Locale.ROOT)));}String pid=required(str(p,"id"),"phase id").toLowerCase(Locale.ROOT);phases.add(new Phase(pid,optional(str(p,"title"),pid),optional(str(p,"description"),""),List.copyOf(objectives),steps(p.get("on-start"),"phase on-start"),steps(p.get("on-complete"),"phase on-complete"),List.copyOf(branches)));}if(phases.isEmpty())throw new IllegalArgumentException("quest needs at least one phase");boolean repeat=typedBoolean(s,"repeatable",false);int maximum=typedInt(s,"maximum-completions",repeat?0:1);if(maximum<0)throw new IllegalArgumentException("maximum-completions cannot be negative");return new Quest(id,s.getString("title",id),s.getString("description",""),List.copyOf(phases),s.contains("requirements")?legacyCondition():s.contains("when")?condition(s.get("when")):new All(List.of()),repeat,s.contains("cooldown")?Durations.parse(s.get("cooldown")):Duration.ZERO,maximum,s.contains("time-limit")?Durations.parse(s.get("time-limit")):null,steps(s.get("on-start"),"quest on-start"),steps(s.get("on-complete"),"quest on-complete"),steps(s.get("on-fail"),"quest on-fail"),steps(s.get("on-reset"),"quest on-reset"));}
    private Condition legacyCondition(){throw new IllegalArgumentException("obsolete requirements condition map"+MIGRATION);}

    private Objective objective(Map<?,?> m){reject(m,"effects");String oid=required(str(m,"id"),"objective id"),rawType=required(str(m,"type"),"objective type");ObjectiveType type=enumOrNull(ObjectiveType.class,rawType);String extension=null;Map<String,Object> options=Map.of();long requiredProgress=integer(m,"amount",1);if(type==null){extension=custom(rawType,ExpansionTypes.Objective.class);ExpansionTypes.Objective handler=api.handler(ExpansionTypes.Objective.class,extension).orElseThrow();ExpansionTypes.ObjectiveDefinition d=handler.parse(stringMap(m));options=d.data();api.validateEditorData(handler,options,"objective "+oid);requiredProgress=d.required();type=ObjectiveType.CUSTOM;}else Validation.keys(m,Set.of("id","title","description","type","material","entity","amount","npc","instance","location","radius","duration","optional","hidden","on-start","on-progress","on-complete"));Material material=m.containsKey("material")?material(str(m,"material")):null;EntityType entity=m.containsKey("entity")?entity(str(m,"entity")):null;int amount=integer(m,"amount",1);String npc=m.containsKey("npc")?id(str(m,"npc"),"npc"):null;Position position=null;if(m.get("location")!=null){Map<?,?> l=asMap(m.get("location"));Validation.keys(l,Set.of("world","x","y","z"));position=new Position(required(str(l,"world"),"world"),number(l,"x"),number(l,"y"),number(l,"z"));}Duration duration=m.containsKey("duration")?Durations.parse(m.get("duration")):null;if(amount<1)throw new IllegalArgumentException("objective amount must be positive");if(m.containsKey("radius")&&number(m,"radius")<=0)throw new IllegalArgumentException("objective radius must be positive");switch(type){case COLLECT_ITEM,DELIVER_ITEM->required(material,type+" material");case KILL_ENTITY->required(entity,"kill-entity entity");case TALK_TO_NPC->required(npc,"talk-to-npc npc");case GO_TO_LOCATION,INTERACT_BLOCK->required(position,type+" location");case WAIT,SURVIVE->required(duration,type+" duration");default->{}}ProgressHook progress=progressHook(m,type==ObjectiveType.WAIT||type==ObjectiveType.SURVIVE);return new Objective(oid,optional(str(m,"title"),oid),optional(str(m,"description"),""),type,material,entity,amount,npc,str(m,"instance"),position,m.containsKey("radius")?number(m,"radius"):1.5,duration,strictBool(m,"optional",false),strictBool(m,"hidden",false),steps(m.get("on-start"),"objective on-start"),progress,steps(m.get("on-complete"),"objective on-complete"),extension,options,requiredProgress);}
    private ProgressHook progressHook(Map<?,?> m,boolean duration){Object raw=m.get("on-progress");if(raw==null)return new ProgressHook(0,List.of());if(!(raw instanceof Map<?,?> p))throw new IllegalArgumentException("on-progress must contain every and script");reject(p,"effects");Validation.keys(p,Set.of("every","script"));Object every=p.get("every");long interval=every==null?1:every instanceof Number n?n.longValue():duration?Durations.parse(every).toMillis():Long.parseLong(String.valueOf(every));if(interval<1)throw new IllegalArgumentException("on-progress every must be positive");return new ProgressHook(interval,steps(p.get("script"),"on-progress script"));}

    private List<Step> steps(Object raw,String what){if(raw==null)return List.of();if(!(raw instanceof List<?> list))throw new IllegalArgumentException(what+" must be a list");List<Step> out=new ArrayList<>();for(Object value:list)out.add(step(asMap(value)));return List.copyOf(out);}
    private Step step(Map<?,?> m){reject(m,"use","actions","effects");String type=required(str(m,"type"),"script step type");if(!type.equals(type.toLowerCase(Locale.ROOT))||type.contains("_"))throw new IllegalArgumentException("step type must be lowercase kebab-case: "+type);return switch(type){
        case "say"->{Validation.keys(m,Set.of("type","text","text-key","translations","variants","delay"));List<WeightedText> variants=new ArrayList<>();Object raw=m.get("variants");if(raw instanceof List<?> list)for(Object v:list){Map<?,?> x=asMap(v);Validation.keys(x,Set.of("text","weight"));variants.add(new WeightedText(required(str(x,"text"),"variant text"),positive(integer(x,"weight",1),"weight")));}String text=str(m,"text"),textKey=str(m,"text-key");Map<String,String> translations=new LinkedHashMap<>();if(m.get("translations")!=null)asMap(m.get("translations")).forEach((locale,value)->translations.put(required(String.valueOf(locale),"translation locale").toLowerCase(Locale.ROOT).replace('_','-'),required(String.valueOf(value),"translation text")));if((text==null||text.isBlank())&&variants.isEmpty()&&(textKey==null||textKey.isBlank()))throw new IllegalArgumentException("say needs text, variants, or text-key");if(!translations.isEmpty()&&(textKey==null||textKey.isBlank()))throw new IllegalArgumentException("say translations need text-key");yield new Say(text,textKey,Map.copyOf(translations),List.copyOf(variants),m.containsKey("delay")?Durations.parse(m.get("delay")):defaultDelay);}
        case "if"->{Validation.keys(m,Set.of("type","when","then","else"));yield new If(condition(required(m.get("when"),"if when")),steps(m.get("then"),"then"),steps(m.get("else"),"else"));}
        case "choice"->{Validation.keys(m,Set.of("type","options"));if(!(m.get("options") instanceof List<?> list))throw new IllegalArgumentException("choice options must be a list");List<ChoiceOption> options=new ArrayList<>();for(Object raw:list){Map<?,?> o=asMap(raw);reject(o,"actions","effects","conditions");Validation.keys(o,Set.of("text","when","script"));options.add(new ChoiceOption(required(str(o,"text"),"choice text"),o.containsKey("when")?condition(o.get("when")):new All(List.of()),steps(o.get("script"),"choice option script")));}if(options.isEmpty())throw new IllegalArgumentException("choice needs options");yield new ChoiceStep(List.copyOf(options));}
        case "goto"->{Validation.keys(m,Set.of("type","node","dialogue"));String node=str(m,"node"),dialogue=str(m,"dialogue");if(node==null&&dialogue==null)throw new IllegalArgumentException("goto needs node or dialogue");yield new Goto(node,dialogue);}
        case "end-dialogue"->{Validation.keys(m,Set.of("type"));yield new EndDialogue();}case "stop"->{Validation.keys(m,Set.of("type"));yield new Stop();}case "wait"->{Validation.keys(m,Set.of("type","duration"));yield new Wait(Durations.parse(required(m.get("duration"),"wait duration")));}
        case "random"->{Validation.keys(m,Set.of("type","options"));if(!(m.get("options") instanceof List<?> list))throw new IllegalArgumentException("random options must be a list");List<WeightedScript> options=new ArrayList<>();for(Object raw:list){Map<?,?> o=asMap(raw);Validation.keys(o,Set.of("weight","script"));options.add(new WeightedScript(positive(integer(o,"weight",1),"weight"),steps(o.get("script"),"random script")));}if(options.isEmpty())throw new IllegalArgumentException("random needs options");yield new RandomStep(List.copyOf(options));}
        case "run-script"->{Validation.keys(m,Set.of("type","script","inputs"));if(!m.containsKey("inputs"))throw new IllegalArgumentException("run-script requires an inputs mapping, even when empty");Object rawInputs=m.get("inputs");if(!(rawInputs instanceof Map<?,?>)&&!(rawInputs instanceof ConfigurationSection))throw new IllegalArgumentException("run-script inputs must be a mapping");yield new RunScript(required(str(m,"script"),"script name"),stringMap(asMap(rawInputs)));}
        default->{String key=PersonaApi.canonical(type);if(api!=null&&!api.registeredTypes(ExpansionTypes.Command.class).contains(key))throw new IllegalArgumentException("unavailable command type "+key);if(api==null&&(type.contains(":")||!BUILTIN_COMMANDS.contains(type)))throw new IllegalArgumentException("unavailable command type "+key);if(!type.contains(":")){Set<String> keys=new LinkedHashSet<>(commandOptionKeys(type));keys.addAll(Set.of("type","on-success","on-failure"));Validation.keys(m,keys);}Map<String,Object> data=stringMap(m);data.keySet().removeAll(Set.of("type","on-success","on-failure"));if(api!=null&&type.contains(":")){ExpansionTypes.Command handler=api.handler(ExpansionTypes.Command.class,key).orElseThrow();data=new LinkedHashMap<>(handler.parse(Map.copyOf(data)));api.validateEditorData(handler,data,"command "+key);}validateBuiltinYaml(type,data);yield new Command(key,Map.copyOf(data),steps(m.get("on-success"),"on-success"),steps(m.get("on-failure"),"on-failure"));}
    };}
    public static Set<String> commandOptionKeys(String type){return switch(type){case "start-quest","finish-quest"->Set.of("quest");case "deliver-items"->Set.of("quest","objective");case "set-flag"->Set.of("flag","value");case "set-variable"->Set.of("variable","name","value","operation");case "message","action-bar","broadcast","npc-speak"->Set.of("text","audience","radius","location");case "title"->Set.of("title","subtitle","fade-in","stay","fade-out","audience","radius","location");case "play-sound"->Set.of("sound","volume","pitch","audience","radius","location");case "particle"->Set.of("particle","count","offset-x","offset-y","offset-z","extra","audience","radius","location");case "give-item","take-item"->Set.of("material","amount");case "give-experience"->Set.of("amount");case "run-command"->Set.of("command","as");case "teleport","lightning-effect","npc-move"->Set.of("location");case "potion-effect"->Set.of("effect","duration","amplifier","ambient","particles");case "spawn-entity"->Set.of("entity","location");case "set-block"->Set.of("material","location");case "npc-animation"->Set.of("animation");default->Set.of();};}
    private void validateBuiltinYaml(String type,Map<String,Object> data){switch(type){case "start-quest","finish-quest"->required(data.get("quest"),type+" quest");case "deliver-items"->{required(data.get("quest"),"deliver-items quest");required(data.get("objective"),"deliver-items objective");}case "give-item","take-item","set-block"->{material(String.valueOf(required(data.get("material"),type+" material")));if((type.equals("give-item")||type.equals("take-item"))&&integer(data,"amount",1)<1)throw new IllegalArgumentException(type+" amount must be positive");}case "give-experience"->{if(integer(data,"amount",1)<1)throw new IllegalArgumentException("give-experience amount must be positive");}case "set-flag"->{required(data.get("flag"),"set-flag flag");strictBool(data,"value",true);}case "set-variable"->{required(data.containsKey("variable")?data.get("variable"):data.get("name"),"set-variable variable");String operation=optional(str(data,"operation"),"set");if(!Set.of("set","add","subtract","multiply","delete").contains(operation))throw new IllegalArgumentException("invalid set-variable operation "+operation);if(!operation.equals("delete"))required(data.get("value"),"set-variable value");}case "message","action-bar","broadcast","npc-speak"->required(data.get("text"),type+" text");case "title"->{if(!data.containsKey("title")&&!data.containsKey("subtitle"))throw new IllegalArgumentException("title needs title or subtitle");for(String key:Set.of("fade-in","stay","fade-out"))optionalPositiveDuration(data,key);}case "play-sound"->{required(data.get("sound"),"play-sound sound");positiveNumber(data,"volume",1);double pitch=number(data,"pitch",1);if(pitch<0||pitch>2)throw new IllegalArgumentException("play-sound pitch must be between 0 and 2");}case "particle"->{required(data.get("particle"),"particle particle");if(integer(data,"count",1)<0)throw new IllegalArgumentException("particle count cannot be negative");for(String key:Set.of("offset-x","offset-y","offset-z","extra"))number(data,key,0);}case "run-command"->{required(data.get("command"),"run-command command");String as=optional(str(data,"as"),"console");if(!Set.of("console","player").contains(as))throw new IllegalArgumentException("run-command as must be console or player");}case "potion-effect"->{required(data.get("effect"),"potion-effect effect");optionalPositiveDuration(data,"duration");if(integer(data,"amplifier",0)<0)throw new IllegalArgumentException("potion-effect amplifier cannot be negative");strictBool(data,"ambient",false);strictBool(data,"particles",true);}case "spawn-entity"->entity(String.valueOf(required(data.get("entity"),"spawn-entity entity")));case "npc-animation"->{String animation=String.valueOf(required(data.get("animation"),"npc-animation animation"));if(!Set.of("swing-main-hand","swing-off-hand","SWING_MAIN_HAND","SWING_OFF_HAND").contains(animation))throw new IllegalArgumentException("invalid npc-animation animation "+animation);}default->{}}for(String key:Set.of("radius"))if(data.containsKey(key))positiveNumber(data,key,1);if(data.containsKey("audience")&&!Set.of("player","server","world","nearby").contains(String.valueOf(data.get("audience"))))throw new IllegalArgumentException("audience must be player, server, world, or nearby");if(data.containsKey("location"))validateLocation(data.get("location"));}

    private Condition condition(Object raw){
        if(raw instanceof List<?> list)return new All(list.stream().map(this::condition).toList());
        Map<?,?> m=asMap(raw);String type=required(str(m,"type"),"condition type");
        if(!type.equals(type.toLowerCase(Locale.ROOT))||type.contains("_"))throw new IllegalArgumentException("condition type must be lowercase kebab-case: "+type);
        return switch(type){
            case "all"->{Validation.keys(m,Set.of("type","conditions"));yield new All(conditionChildren(m));}
            case "any"->{Validation.keys(m,Set.of("type","conditions"));yield new Any(conditionChildren(m));}
            case "not"->{Validation.keys(m,Set.of("type","when"));yield new Not(condition(required(m.get("when"),"not when")));}
            case "quest-state"->{Validation.keys(m,Set.of("type","quest","state"));yield new QuestStateCondition(id(str(m,"quest"),"quest"),enumValue(QuestState.class,str(m,"state"),"quest state"));}
            case "item-count"->{Validation.keys(m,Set.of("type","material","amount"));yield new ItemCount(material(str(m,"material")),positive(integer(m,"amount",1),"amount"));}
            case "flag"->{Validation.keys(m,Set.of("type","name","value"));yield new Flag(required(str(m,"name"),"flag name"),strictBool(m,"value",true));}
            case "variable"->{Validation.keys(m,Set.of("type","name","operator","value"));yield new VariableCondition(required(str(m,"name"),"variable name"),enumValue(Comparison.class,optional(str(m,"operator"),"equals"),"variable operator"),optional(str(m,"value"),""));}
            case "permission"->{Validation.keys(m,Set.of("type","permission"));yield new PermissionCondition(required(str(m,"permission"),"permission"));}
            case "world"->{Validation.keys(m,Set.of("type","world"));yield new WorldCondition(required(str(m,"world"),"world"));}
            case "chance"->{Validation.keys(m,Set.of("type","chance"));double chance=number(m,"chance");if(chance<0||chance>1)throw new IllegalArgumentException("chance must be between 0 and 1");yield new ChanceCondition(chance);}
            default->{String key=custom(type,ExpansionTypes.Condition.class);Map<String,Object> data=stringMap(m);data.remove("type");ExpansionTypes.Condition handler=api.handler(ExpansionTypes.Condition.class,key).orElseThrow();data=new LinkedHashMap<>(handler.parse(Map.copyOf(data)));api.validateEditorData(handler,data,"condition "+key);yield new CustomCondition(key,Map.copyOf(data));}
        };
    }
    private List<Condition> conditionChildren(Map<?,?> m){Object raw=m.get("conditions");if(!(raw instanceof List<?> list))throw new IllegalArgumentException("composite condition needs conditions list");return list.stream().map(this::condition).toList();}

    private void validate(Map<String, Npc> npcs,
                          Map<String, Dialogue> dialogues,
                          Map<String, Quest> quests,
                          Map<String, ScriptDefinition> scripts,
                          Map<String, BehaviorDefinition> behaviors) {
      for (Npc n : npcs.values()) {
        select(n);
        if (n.sharedBehavior() != null) {
          BehaviorDefinition b = behaviors.get(n.sharedBehavior());
          if (b == null)
            error(n.id() + " references missing shared behavior " +
                  n.sharedBehavior());
          else if (b.scope() != BehaviorScope.SHARED)
            error(n.id() + " shared behavior has player scope");
        }
        if (n.playerBehavior() != null) {
          BehaviorDefinition b = behaviors.get(n.playerBehavior());
          if (b == null)
            error(n.id() + " references missing player behavior " +
                  n.playerBehavior());
          else if (b.scope() != BehaviorScope.PLAYER)
            error(n.id() + " player behavior has shared scope");
        }
        for (String behaviorId : attachedBehaviors(n, behaviors))
          for (var node : behaviors.get(behaviorId).nodes().values())
            for (String key : Set.of("anchor", "source", "destination")) {
              Object anchor = node.options().get(key);
              if (anchor != null &&
                  !n.anchors().containsKey(String.valueOf(anchor)))
                error(n.id() + " behavior " + behaviorId +
                      " references missing " + key + " anchor " + anchor);
            }
        for (DialogueRegistration r : n.dialogues()) {
          if (!dialogues.containsKey(r.dialogueId()))
            error(n.id() + " references missing dialogue " + r.dialogueId());
          validateCondition(r.condition(), quests);
        }
        validateSteps(n.onInteract(), null, dialogues, quests, scripts, false);
        validateSteps(n.onNoDialogue(), null, dialogues, quests, scripts,
                      false);
      }
      for (Dialogue d : dialogues.values()) {
        select(d);
        if (!d.nodes().containsKey(d.start()))
          error(d.id() + " has missing start node " + d.start());
        for (Node n : d.nodes().values())
          validateSteps(n.script(), d, dialogues, quests, scripts, true);
      }
      for (Quest q : quests.values()) {
        select(q);
        validateCondition(q.requirements(), quests);
        Set<String> phaseIds = new HashSet<>();
        for (Phase p : q.phases())
          if (!phaseIds.add(p.id()))
            error(q.id() + " has duplicate phase " + p.id());
        for (Phase p : q.phases()) {
          if (p.objectives().isEmpty())
            error(q.id() + " phase " + p.id() + " has no objectives");
          Set<String> ids = new HashSet<>();
          for (Objective o : p.objectives()) {
            if (!ids.add(o.id()))
              error(q.id() + " phase " + p.id() + " has duplicate objective " +
                    o.id());
            if (o.npc() != null && !npcs.containsKey(o.npc()))
              error(q.id() + " references missing NPC " + o.npc());
            validateSteps(o.onStart(), null, dialogues, quests, scripts, false);
            validateSteps(o.onProgress().script(), null, dialogues, quests,
                          scripts, false);
            validateSteps(o.onComplete(), null, dialogues, quests, scripts,
                          false);
          }
          for (PhaseBranch b : p.branches()) {
            validateCondition(b.condition(), quests);
            if (!b.nextPhase().equals("end") &&
                !phaseIds.contains(b.nextPhase()))
              error(q.id() + " branch references missing phase " +
                    b.nextPhase());
          }
          validateSteps(p.onStart(), null, dialogues, quests, scripts, false);
          validateSteps(p.onComplete(), null, dialogues, quests, scripts,
                        false);
        }
        validateSteps(q.onStart(), null, dialogues, quests, scripts, false);
        validateSteps(q.onComplete(), null, dialogues, quests, scripts, false);
        validateSteps(q.onFail(), null, dialogues, quests, scripts, false);
        validateSteps(q.onReset(), null, dialogues, quests, scripts, false);
      }
      validateBehaviorLeaves(behaviors, quests, scripts);
      selectFile("scripts.yml");
    }

    private Set<String> attachedBehaviors(Npc npc,
                                          Map<String, BehaviorDefinition> all) {
      Set<String> out = new LinkedHashSet<>();
      collectBehavior(npc.sharedBehavior(), all, out);
      collectBehavior(npc.playerBehavior(), all, out);
      out.retainAll(all.keySet());
      return out;
    }
    private void collectBehavior(String id, Map<String, BehaviorDefinition> all,
                                 Set<String> out) {
      if (id == null || !out.add(id))
        return;
      BehaviorDefinition b = all.get(id);
      if (b != null)
        for (var n : b.nodes().values())
          collectBehavior(n.subtree(), all, out);
    }
    private void
    validateBehaviorLeaves(Map<String, BehaviorDefinition> behaviors,
                           Map<String, Quest> quests,
                           Map<String, ScriptDefinition> scripts) {
      for (BehaviorDefinition behavior : behaviors.values()) {
        source = behaviorSources.getOrDefault(behavior.id(), "behaviors");
        document = null;
        for (var node : behavior.nodes().values())
          try {
            String type = String.valueOf(node.options().get(
                node.type().equals("condition") ? "condition" : "action"));
            if (node.type().equals("condition") &&
                !Set.of("memory", "event").contains(type) &&
                !type.contains(":")) {
              Map<String, Object> map = new LinkedHashMap<>(node.options());
              map.put("type", type);
              map.remove("condition");
              validateCondition(condition(map), quests);
            } else if (node.type().equals("action") && type.equals("script")) {
              String name = String.valueOf(node.options().get("script"));
              if (!scripts.containsKey(name))
                error("behavior " + behavior.id() + " action " + node.id() +
                      " references missing script " + name);
              else for (var input : scripts.get(name).inputs().entrySet())
                if (input.getValue().required() && input.getValue().defaultValue() == null)
                  error("behavior " + behavior.id() + " action " + node.id() +
                        " cannot call script " + name + " because required input " +
                        input.getKey() + " has no default");
            } else if (node.type().equals("action") && type.equals("command")) {
              Map<String, Object> map = new LinkedHashMap<>(node.options());
              map.put("type", map.remove("command"));
              map.remove("action");
              Step parsed = step(map);
              if (parsed instanceof Command command)
                validateCommand(command, quests);
            }
          } catch (RuntimeException e) {
            error("behavior " + behavior.id() + " node " + node.id() + ": " +
                  e.getMessage());
          }
      }
    }
    private void validateSteps(List<Step> values, Dialogue owner,
                               Map<String, Dialogue> dialogues,
                               Map<String, Quest> quests,
                               Map<String, ScriptDefinition> scripts,
                               boolean dialogue) {
      validateSteps(values, owner, dialogues, quests, scripts, dialogue,
                    new HashSet<>());
    }
    private void validateSteps(List<Step> values, Dialogue owner,
                               Map<String, Dialogue> dialogues,
                               Map<String, Quest> quests,
                               Map<String, ScriptDefinition> scripts,
                               boolean dialogue, Set<String> reusableStack) {
      for (Step s : values) {
        if (s instanceof If x) {
          validateCondition(x.when(), quests);
          validateSteps(x.thenScript(), owner, dialogues, quests, scripts,
                        dialogue, reusableStack);
          validateSteps(x.elseScript(), owner, dialogues, quests, scripts,
                        dialogue, reusableStack);
        } else if (s instanceof ChoiceStep x) {
          if (!dialogue)
            error("choice is only valid in dialogue scripts");
          for (ChoiceOption o : x.options()) {
            validateCondition(o.when(), quests);
            validateSteps(o.script(), owner, dialogues, quests, scripts,
                          dialogue, reusableStack);
          }
        } else if (s instanceof Goto g) {
          if (!dialogue)
            error("goto is only valid in dialogue scripts");
          Dialogue target =
              g.dialogue() == null ? owner : dialogues.get(g.dialogue());
          if (g.dialogue() != null && target == null)
            error("goto references missing dialogue " + g.dialogue());
          if (target != null && g.node() != null &&
              !target.nodes().containsKey(g.node()))
            error("goto references missing node " + g.node());
        } else if (s instanceof EndDialogue && !dialogue)
          error("end-dialogue is only valid in dialogue scripts");
        else if (s instanceof RunScript r) {
          if (!scripts.containsKey(r.script()))
            error("run-script references missing script " + r.script());
          else validateCall(r,scripts.get(r.script()));
        } else if (s instanceof RandomStep r)
          r.options().forEach(o
                              -> validateSteps(o.script(), owner, dialogues,
                                               quests, scripts, dialogue,
                                               reusableStack));
        else if (s instanceof Command c) {
          validateCommand(c, quests);
          validateSteps(c.onSuccess(), owner, dialogues, quests, scripts,
                        dialogue, reusableStack);
          validateSteps(c.onFailure(), owner, dialogues, quests, scripts,
                        dialogue, reusableStack);
        }
      }
    }
    private void validateCommand(Command c, Map<String, Quest> quests) {
      String type = c.type();
      if (type.equals("persona:start-quest") ||
          type.equals("persona:finish-quest")) {
        String q = String.valueOf(c.options().get("quest"));
        if (!quests.containsKey(q))
          error(type + " references missing quest " + q);
      }
      if (type.equals("persona:deliver-items")) {
        String q = String.valueOf(c.options().get("quest")),
               o = String.valueOf(c.options().get("objective"));
        Quest quest = quests.get(q);
        if (quest == null)
          error(type + " references missing quest " + q);
        else if (quest.phases()
                     .stream()
                     .flatMap(p -> p.objectives().stream())
                     .noneMatch(x
                                -> x.id().equals(o) &&
                                       x.type() == ObjectiveType.DELIVER_ITEM))
          error("deliver-items references missing delivery objective " + q +
                "/" + o);
      }
    }
    private void validateCondition(Condition c, Map<String, Quest> quests) {
      if (c instanceof All a)
        a.conditions().forEach(x -> validateCondition(x, quests));
      else if (c instanceof Any a)
        a.conditions().forEach(x -> validateCondition(x, quests));
      else if (c instanceof Not n)
        validateCondition(n.condition(), quests);
      else if (c instanceof QuestStateCondition q &&
               !quests.containsKey(q.quest()))
        error("condition references missing quest " + q.quest());
    }
    private void validateCall(RunScript call, ScriptDefinition target) {
      for (String name : call.inputs().keySet())
        if (!target.inputs().containsKey(name))
          error("run-script " + call.script() + " has unknown input " + name);
        else try { ScriptDefinitionLoader.validateLiteral(target.inputs().get(name).type(),call.inputs().get(name),"run-script "+call.script()+" input "+name); }
          catch(IllegalArgumentException failure){error(failure.getMessage());}
      for (var parameter : target.inputs().entrySet())
        if (parameter.getValue().required() &&
            !call.inputs().containsKey(parameter.getKey()))
          error("run-script " + call.script() + " is missing required input " +
                parameter.getKey());
    }

    private String custom(String raw,Class<?> category){if(api==null||!raw.contains(":"))throw new IllegalArgumentException("unknown "+category.getSimpleName().toLowerCase(Locale.ROOT)+" type "+raw);String key=PersonaApi.canonical(raw);if(!api.registeredTypes(category).contains(key))throw new IllegalArgumentException("unavailable namespaced type "+key);return key;}
    private Material material(String raw){Material m=materials.apply(required(raw,"material"));if(m==null)throw new IllegalArgumentException("invalid material "+raw);return m;}private EntityType entity(String raw){EntityType e=entities.apply(required(raw,"entity"));if(e==null)throw new IllegalArgumentException("invalid entity type "+raw);return e;}private static Material bukkitMaterial(String raw){Material m=Material.matchMaterial(raw);return m!=null&&(m.isItem()||m.isBlock())?m:null;}private static EntityType bukkitEntity(String raw){NamespacedKey k=NamespacedKey.fromString(raw);return k==null?null:Registry.ENTITY_TYPE.get(k);}
    private String id(String value,String what){return Ids.require(value,source+" "+what);}private static String anchorId(String value){if(value==null||!value.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid anchor ID "+value);return value;}
    private void select(Object value){source=sources.get(value);document=source==null?null:Validation.Source.read(root,new File(root,source));}private void selectFile(String name){source=name;File file=new File(root,name);document=file.isFile()?Validation.Source.read(root,file):null;}
    private void error(Exception e){error(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}private void error(String e){if(document!=null)errors.add(document.at(hint(e),e));else if(source!=null&&source.matches(".*:\\d+:\\d+$"))errors.add(source+": "+e);else errors.add(Objects.toString(source,"content")+":1:1: "+e);}
    private static void reject(ConfigurationSection s,String...keys){for(String k:keys)if(s.contains(k))throw new IllegalArgumentException("obsolete key '"+k+"'"+MIGRATION);}private static void reject(Map<?,?> m,String...keys){for(String k:keys)if(m.containsKey(k))throw new IllegalArgumentException("obsolete key '"+k+"'"+MIGRATION);}
    private static List<Map<?,?>> maps(ConfigurationSection s,String path){return new ArrayList<>(s.getMapList(path));}private static Map<?,?> asMap(Object raw){if(raw instanceof Map<?,?> m)return m;if(raw instanceof ConfigurationSection s)return s.getValues(false);throw new IllegalArgumentException("expected map, got "+raw);}private static Map<String,Object> stringMap(Map<?,?> m){Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    private static String str(Map<?,?> m,String k){Object v=m.get(k);return v==null?null:String.valueOf(v);}private static String optional(String v,String fallback){return v==null?fallback:v;}private static int integer(Map<?,?> m,String k,int fallback){Object v=m.get(k);if(v==null)return fallback;if(!(v instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException(k+" must be an integer");return n.intValue();}private static int positive(int n,String what){if(n<1)throw new IllegalArgumentException(what+" must be positive");return n;}private static double number(Map<?,?> m,String k){Object v=m.get(k);return v instanceof Number n?n.doubleValue():Double.parseDouble(String.valueOf(required(v,k)));}private static double number(Map<?,?> m,String k,double fallback){return m.containsKey(k)?number(m,k):fallback;}private static double positiveNumber(Map<?,?> m,String key,double fallback){double value=number(m,key,fallback);if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException(key+" must be positive");return value;}private static void optionalPositiveDuration(Map<?,?> m,String key){if(m.containsKey(key)&&Durations.parse(m.get(key)).isNegative())throw new IllegalArgumentException(key+" cannot be negative");}private static void validateLocation(Object raw){if(raw instanceof String text){if(!Set.of("player","npc").contains(text))throw new IllegalArgumentException("location must be player, npc, or coordinates");return;}Map<?,?> location=asMap(raw);Validation.keys(location,Set.of("world","x","y","z"));for(String key:Set.of("x","y","z"))number(location,key);}private static boolean strictBool(Map<?,?> m,String key,boolean fallback){if(!m.containsKey(key))return fallback;Object v=m.get(key);if(!(v instanceof Boolean b))throw new IllegalArgumentException(key+" must be true or false");return b;}private static int typedInt(ConfigurationSection s,String key,int fallback){if(!s.contains(key))return fallback;Object v=s.get(key);if(!(v instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException(key+" must be an integer");return n.intValue();}private static boolean typedBoolean(ConfigurationSection s,String key,boolean fallback){if(!s.contains(key))return fallback;if(!s.isBoolean(key))throw new IllegalArgumentException(key+" must be true or false");return s.getBoolean(key);}private static String hint(String message){if(message==null)return null;var quoted=java.util.regex.Pattern.compile("'(.*?)'").matcher(message);if(quoted.find())return quoted.group(1);for(String key:List.of("content-version","id","scripts","phases","objectives","nodes","root","type","anchor","destination","source","script","when"))if(message.contains(key))return key;return null;}
    private static <E extends Enum<E>>E enumValue(Class<E> t,String v,String what){String value=required(v,what);if(!value.equals(value.toLowerCase(Locale.ROOT))||value.contains("_"))throw new IllegalArgumentException(what+" must be lowercase kebab-case: "+value);try{return Enum.valueOf(t,value.toUpperCase(Locale.ROOT).replace('-','_'));}catch(IllegalArgumentException e){throw new IllegalArgumentException("invalid "+what+" "+value);}}private static <E extends Enum<E>>E enumOrNull(Class<E> t,String v){if(!v.equals(v.toLowerCase(Locale.ROOT))||v.contains("_"))throw new IllegalArgumentException(t.getSimpleName()+" value must be lowercase kebab-case: "+v);try{return Enum.valueOf(t,v.toUpperCase(Locale.ROOT).replace('-','_'));}catch(IllegalArgumentException e){return null;}}private static <T>T required(T v,String what){if(v==null||v instanceof String s&&s.isBlank())throw new IllegalArgumentException("missing "+what);return v;}
}
