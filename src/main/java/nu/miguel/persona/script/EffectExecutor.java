package nu.miguel.persona.script;

import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import nu.miguel.persona.Main;
import nu.miguel.persona.content.Content.*;
import nu.miguel.persona.content.Durations;
import nu.miguel.persona.state.PlayerState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.citizens.PersonaTrait;

/** Executes all script-visible side effects. WAIT and SEQUENCE never block the server thread. */
public final class EffectExecutor {
    public record Context(Player player, NPC citizensNpc, Npc npc, Dialogue dialogue, Quest quest, Phase phase,
                          Objective objective, long current, long required) {
        public Context(Player player,NPC citizensNpc,Npc npc,Quest quest,Phase phase,Objective objective,long current,long required){this(player,citizensNpc,npc,null,quest,phase,objective,current,required);}
        public static Context player(Player player) { return new Context(player,null,null,null,null,null,null,0,0); }
        public Context dialogue(Dialogue d){return new Context(player,citizensNpc,npc,d,quest,phase,objective,current,required);}
        public Context quest(Quest q) { return new Context(player,citizensNpc,npc,dialogue,q,phase,objective,current,required); }
        public Context phase(Phase p) { return new Context(player,citizensNpc,npc,dialogue,quest,p,objective,current,required); }
        public Context objective(Objective o,long c,long r) { return new Context(player,citizensNpc,npc,dialogue,quest,phase,o,c,r); }
        public Context withPlayer(Player target) { return new Context(target,citizensNpc,npc,dialogue,quest,phase,objective,current,required); }
    }

    private static final Pattern FLAG=Pattern.compile("<flag:([a-zA-Z0-9_.-]+)>");
    private static final Pattern VARIABLE=Pattern.compile("<variable:([a-zA-Z0-9_.-]+)>");
    private static final Pattern MEMORY=Pattern.compile("<memory:([a-zA-Z0-9_.:-]+)>");
    private static final Pattern NPC_MEMORY=Pattern.compile("<npc-memory:([a-zA-Z0-9_.:-]+)>");
    private static final Pattern EXTENSION_PLACEHOLDER=Pattern.compile("<([a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_.-]*)(?::([^>]*))?>");
    private final Main plugin;
    private final MiniMessage mini=MiniMessage.miniMessage();

    public EffectExecutor(Main plugin){this.plugin=plugin;}
    /** Executes one already validated synchronous built-in mutation. */
    public void executeCommand(String canonicalType,Map<String,Object> options,Context context){
        String local=canonicalType.startsWith("persona:")?canonicalType.substring("persona:".length()):canonicalType;
        EffectType type=EffectType.valueOf(local.toUpperCase(Locale.ROOT).replace('-','_'));
        execute(new Effect(type,options,List.of()),context);
    }
    public void run(List<Effect> effects,Context context){runChain(effects,0,context,()->{});}

    private void runChain(List<Effect> effects,int index,Context context,Runnable done){
        if(index>=effects.size()){done.run();return;}
        Effect effect=effects.get(index);Runnable next=()->runChain(effects,index+1,context,done);
        try{
            if(effect.type()==EffectType.WAIT){Duration d=Durations.parse(effect.options().getOrDefault("duration","1s"));plugin.getServer().getScheduler().runTaskLater(plugin,next,Math.max(1,(long)Math.ceil(d.toMillis()/50.0)));return;}
            if(effect.type()==EffectType.SEQUENCE){runChain(effect.nested(),0,context,next);return;}
            if(effect.type()==EffectType.RANDOM){if(effect.nested().isEmpty()){next.run();return;}Effect selected=weighted(effect.nested());runChain(List.of(selected),0,context,next);return;}
            execute(effect,context);
        }catch(RuntimeException e){plugin.getLogger().warning("Could not execute "+effect.type()+" effect: "+e.getMessage());}
        next.run();
    }

    private Effect weighted(List<Effect> values){int total=values.stream().mapToInt(e->integer(e.options(),"weight",1)).sum();int pick=ThreadLocalRandom.current().nextInt(Math.max(1,total));for(Effect e:values){pick-=integer(e.options(),"weight",1);if(pick<0)return e;}return values.getLast();}

    private void execute(Effect effect,Context c){
        Map<String,Object> o=effect.options();Player p=c.player();
        switch(effect.type()){
            case MESSAGE->targets(c,o).forEach(x->x.sendMessage(text(string(o,"text",""),c)));
            case ACTION_BAR->targets(c,o).forEach(x->x.sendActionBar(text(string(o,"text",""),c)));
            case TITLE->{Component title=text(string(o,"title",""),c),subtitle=text(string(o,"subtitle",""),c);Duration in=duration(o,"fade-in","500ms"),stay=duration(o,"stay","3s"),out=duration(o,"fade-out","500ms");Title value=Title.title(title,subtitle,Title.Times.times(in,stay,out));targets(c,o).forEach(x->x.showTitle(value));}
            case PLAY_SOUND->{String sound=plain(string(o,"sound","minecraft:block.note_block.pling"),c);float volume=(float)number(o,"volume",1),pitch=(float)number(o,"pitch",1);targets(c,o).forEach(x->x.playSound(location(c,o),sound,SoundCategory.MASTER,volume,pitch));}
            case PARTICLE->{Particle particle=Particle.valueOf(keyName(string(o,"particle","minecraft:happy_villager")));Location l=location(c,o);int count=integer(o,"count",1);for(Player x:targets(c,o))x.spawnParticle(particle,l,count,number(o,"offset-x",0),number(o,"offset-y",0),number(o,"offset-z",0),number(o,"extra",0));}
            case GIVE_ITEM->{Material m=material(string(o,"material",null));int amount=integer(o,"amount",1);Map<Integer,ItemStack> overflow=p.getInventory().addItem(new ItemStack(m,amount));overflow.values().forEach(i->p.getWorld().dropItemNaturally(p.getLocation(),i));}
            case TAKE_ITEM->take(p,material(string(o,"material",null)),integer(o,"amount",1));
            case GIVE_EXPERIENCE->p.giveExp(integer(o,"amount",1));
            case SET_FLAG->{PlayerState s=state(p);String name=string(o,"flag","");boolean value=bool(o,"value",true);Boolean old=s.flags().put(name,value);save(s);plugin.behaviors().playerStateChanged(p,"flag-changed",Map.of("name",name,"old",old==null?false:old,"new",value));}
            case SET_VARIABLE->{PlayerState s=state(p);String name=string(o,"variable",string(o,"name","")),old=s.variables().get(name);applyVariable(s,name,enumValue(VariableOperation.class,string(o,"operation","SET")),plain(string(o,"value",""),c));save(s);Map<String,Object> change=new LinkedHashMap<>();change.put("name",name);change.put("old",Objects.toString(old,"<unset>"));change.put("new",Objects.toString(s.variables().get(name),"<unset>"));plugin.behaviors().playerStateChanged(p,"variable-changed",change);}
            case RUN_COMMAND->{String command=plain(string(o,"command",""),c);if(string(o,"as","console").equalsIgnoreCase("player"))p.performCommand(stripSlash(command));else plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),stripSlash(command));}
            case TELEPORT->p.teleport(location(c,o));
            case LIGHTNING_EFFECT->location(c,o).getWorld().strikeLightningEffect(location(c,o));
            case POTION_EFFECT->{NamespacedKey key=NamespacedKey.fromString(string(o,"effect","minecraft:speed"));PotionEffectType type=key==null?null:Registry.EFFECT.get(key);if(type==null)throw new IllegalArgumentException("unknown potion effect");int ticks=(int)Math.max(1,duration(o,"duration","10s").toMillis()/50);p.addPotionEffect(new PotionEffect(type,ticks,integer(o,"amplifier",0),bool(o,"ambient",false),bool(o,"particles",true)));}
            case BROADCAST->plugin.getServer().broadcast(text(string(o,"text",""),c));
            case SPAWN_ENTITY->{NamespacedKey key=NamespacedKey.fromString(string(o,"entity",null));var type=key==null?null:Registry.ENTITY_TYPE.get(key);if(type==null)throw new IllegalArgumentException("unknown entity");location(c,o).getWorld().spawnEntity(location(c,o),type);}
            case SET_BLOCK->location(c,o).getBlock().setType(material(string(o,"material",null)));
            case NPC_ANIMATION->{if(c.citizensNpc()!=null&&c.citizensNpc().getEntity() instanceof LivingEntity living){String animation=string(o,"animation","SWING_MAIN_HAND");if(animation.equalsIgnoreCase("SWING_OFF_HAND"))living.swingOffHand();else living.swingMainHand();}}
            case NPC_SPEAK->{String name=c.npc()==null?"NPC":c.npc().displayName();targets(c,o).forEach(x->x.sendMessage(mini.deserialize(replace(name,c)).append(Component.text(": ")).append(text(string(o,"text",""),c))));}
            case NPC_MOVE->{if(c.citizensNpc()!=null)c.citizensNpc().getNavigator().setTarget(location(c,o));}
            default->{}
        }
    }

    public String replace(String input,Context c){
        if(input==null)return "";Player p=c.player();String value=input
                .replace("<player>",p.getName()).replace("<world>",p.getWorld().getName())
                .replace("<x>",String.valueOf(p.getLocation().getBlockX())).replace("<y>",String.valueOf(p.getLocation().getBlockY())).replace("<z>",String.valueOf(p.getLocation().getBlockZ()))
                .replace("<npc>",c.npc()==null?"":c.npc().displayName()).replace("<quest-title>",c.quest()==null?"":c.quest().title())
                .replace("<phase-title>",c.phase()==null?"":c.phase().title()).replace("<objective-title>",c.objective()==null?"":c.objective().title())
                .replace("<current>",String.valueOf(c.current())).replace("<required>",String.valueOf(c.required())).replace("<remaining>",String.valueOf(Math.max(0,c.required()-c.current())));
        PlayerState state=plugin.states().require(p);if(state==null)return value;
        value=replacePattern(value,FLAG,m->String.valueOf(state.flags().getOrDefault(m.group(1),false)));
        value=replacePattern(value,VARIABLE,m->state.variables().getOrDefault(m.group(1),""));
        PersonaTrait trait=c.citizensNpc()==null?null:c.citizensNpc().getTraitNullable(PersonaTrait.class);if(trait!=null&&trait.bound()){String instance=trait.instanceId()==null?Objects.toString(trait.baseNpc(),c.citizensNpc().getUniqueId().toString()):trait.instanceId();value=replaceMemory(value,MEMORY,p.getUniqueId(),trait.definitionId(),instance);value=replaceMemory(value,NPC_MEMORY,null,trait.definitionId(),instance);}
        return replacePattern(value,EXTENSION_PLACEHOLDER,m->{String type=m.group(1);try{var handler=plugin.api().handler(ExpansionTypes.Placeholder.class,type);if(handler.isEmpty()){plugin.getLogger().warning("Placeholder "+type+" is unavailable");return "";}return handler.get().resolve(plugin.api().context(c,type),m.group(2)==null?"":m.group(2));}catch(RuntimeException e){plugin.getLogger().warning("Placeholder "+type+" failed: "+e.getMessage());return "";}});
    }
    private String replaceMemory(String input,Pattern pattern,UUID player,String npc,String instance){return replacePattern(input,pattern,m->plugin.memories().get(player,npc,instance,m.group(1)).map(v->String.valueOf(v.value())).orElse(""));}
    private String replacePattern(String input,Pattern pattern,java.util.function.Function<Matcher,String> replacement){Matcher matcher=pattern.matcher(input);StringBuffer out=new StringBuffer();while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(replacement.apply(matcher)));matcher.appendTail(out);return out.toString();}
    private Component text(String value,Context c){return mini.deserialize(replace(value,c));}private String plain(String value,Context c){return mini.stripTags(replace(value,c));}

    private Collection<Player> targets(Context c,Map<String,Object> o){String audience=string(o,"audience","player").toLowerCase(Locale.ROOT);if(audience.equals("server"))return new ArrayList<>(plugin.getServer().getOnlinePlayers());if(audience.equals("world"))return c.player().getWorld().getPlayers();if(audience.equals("nearby")){double radius=number(o,"radius",16);Location l=location(c,o);return l.getWorld().getNearbyEntities(l,radius,radius,radius,e->e instanceof Player).stream().map(e->(Player)e).toList();}return List.of(c.player());}
    private Location location(Context c,Map<String,Object> o){Object raw=o.get("location");if(raw==null||String.valueOf(raw).equalsIgnoreCase("player"))return c.player().getLocation();if(String.valueOf(raw).equalsIgnoreCase("npc")&&c.citizensNpc()!=null&&c.citizensNpc().isSpawned())return c.citizensNpc().getEntity().getLocation();if(raw instanceof Map<?,?> m){Object worldName=m.containsKey("world")?m.get("world"):c.player().getWorld().getName();World world=plugin.getServer().getWorld(String.valueOf(worldName));if(world==null)throw new IllegalArgumentException("unknown world");return new Location(world,mapNumber(m,"x"),mapNumber(m,"y"),mapNumber(m,"z"));}return c.player().getLocation();}
    private static void take(Player p,Material m,int amount){for(ItemStack item:p.getInventory().getStorageContents()){if(item==null||item.getType()!=m)continue;int n=Math.min(amount,item.getAmount());item.setAmount(item.getAmount()-n);amount-=n;if(amount<=0)return;}}
    public static void applyVariable(PlayerState s,String name,VariableOperation operation,String value){if(operation==VariableOperation.DELETE){s.variables().remove(name);return;}if(operation==VariableOperation.SET){s.variables().put(name,value);return;}double old=parseNumber(s.variables().getOrDefault(name,"0")),operand=parseNumber(value);double result=switch(operation){case ADD->old+operand;case SUBTRACT->old-operand;case MULTIPLY->old*operand;default->operand;};s.variables().put(name,result==Math.rint(result)?String.valueOf((long)result):String.valueOf(result));}
    private PlayerState state(Player p){PlayerState s=plugin.states().require(p);if(s==null)throw new IllegalStateException("player state is loading");return s;}private void save(PlayerState s){plugin.states().save(s);}
    private static double parseNumber(String v){try{return Double.parseDouble(v);}catch(NumberFormatException e){return 0;}}
    private static String stripSlash(String s){return s.startsWith("/")?s.substring(1):s;}private static Material material(String s){Material m=Material.matchMaterial(s);if(m==null)throw new IllegalArgumentException("unknown material "+s);return m;}
    private static String keyName(String raw){int colon=raw.indexOf(':');return (colon>=0?raw.substring(colon+1):raw).toUpperCase(Locale.ROOT).replace('.','_');}
    private static Duration duration(Map<String,Object> m,String k,String d){return Durations.parse(m.getOrDefault(k,d));}private static String string(Map<String,Object> m,String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}private static int integer(Map<String,Object> m,String k,int d){Object v=m.get(k);return v instanceof Number n?n.intValue():v==null?d:Integer.parseInt(String.valueOf(v));}private static double number(Map<String,Object> m,String k,double d){Object v=m.get(k);return v instanceof Number n?n.doubleValue():v==null?d:Double.parseDouble(String.valueOf(v));}private static boolean bool(Map<String,Object> m,String k,boolean d){Object v=m.get(k);return v==null?d:v instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(v));}private static double mapNumber(Map<?,?> m,String k){Object v=m.get(k);if(v instanceof Number n)return n.doubleValue();return Double.parseDouble(String.valueOf(v));}private static <E extends Enum<E>>E enumValue(Class<E> type,String value){return Enum.valueOf(type,value.toUpperCase(Locale.ROOT).replace('-','_'));}
}
