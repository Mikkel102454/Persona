package nu.miguel.persona.editor;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import nu.miguel.persona.Main;
import nu.miguel.persona.api.NpcMemoryService;
import nu.miguel.persona.behavior.*;
import nu.miguel.persona.citizens.PersonaTrait;
import nu.miguel.persona.content.Content;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.persona.state.PlayerState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ageable;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Captures immutable, privacy-filtered live state. Must be called on the server thread. */
public final class LiveSnapshotBuilder {
    private LiveSnapshotBuilder(){}
    public static LiveStateSnapshot capture(Main plugin,LiveSubscribeRequest request,SessionRestrictions restrictions,long revision,boolean full){
        if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("Live snapshots must be captured on the server thread");
        Filter filter=new Filter(request.filter(),restrictions);Set<LiveTopic> topics=request.topics();
        List<LiveStateSnapshot.Behavior> behaviors=topics.contains(LiveTopic.BEHAVIORS)?behaviors(plugin,filter):List.of();
        List<LiveStateSnapshot.Player> players=topics.contains(LiveTopic.PLAYERS)?players(plugin,filter,behaviors):List.of();
        List<LiveStateSnapshot.Npc> npcs=topics.contains(LiveTopic.NPCS)?npcs(plugin,filter,behaviors):List.of();
        List<LiveStateSnapshot.Quest> quests=topics.contains(LiveTopic.QUESTS)?quests(plugin,filter):List.of();
        List<LiveStateSnapshot.Dialogue> dialogues=topics.contains(LiveTopic.DIALOGUES)?dialogues(plugin,filter):List.of();
        List<LiveStateSnapshot.Memory> memories=topics.contains(LiveTopic.MEMORIES)?memories(plugin,filter):List.of();
        List<LiveStateSnapshot.GraphTrace> traces=topics.contains(LiveTopic.TRACES)?traces(plugin,filter):List.of();
        var counts=plugin.behaviors().projections().counts();var metrics=plugin.behaviors().liveMetrics();LiveStateSnapshot.Server server=topics.contains(LiveTopic.SERVER)
                ?new LiveStateSnapshot.Server(metrics.evaluated(),metrics.tickNanos(),metrics.wakeQueue(),behaviors.stream().mapToLong(LiveStateSnapshot.Behavior::droppedEvents).sum(),metrics.persistenceQueue(),counts.server(),counts.serverLimit(),false):null;
        return new LiveStateSnapshot(Protocol.VERSION,request.subscriptionId(),revision,System.currentTimeMillis(),full,players,npcs,behaviors,quests,dialogues,memories,traces,server,List.of());
    }
    private static List<LiveStateSnapshot.Player> players(Main plugin,Filter filter,List<LiveStateSnapshot.Behavior> behaviors){List<LiveStateSnapshot.Player> result=new ArrayList<>();for(Player player:plugin.getServer().getOnlinePlayers()){
        if(!filter.player(player.getUniqueId())||!filter.world(player.getWorld().getName()))continue;PlayerState state=plugin.states().require(player);List<String> active=state==null?List.of():state.quests().keySet().stream().sorted().toList();int runtimes=(int)behaviors.stream().filter(value->player.getUniqueId().equals(value.playerId())).count();result.add(new LiveStateSnapshot.Player(player.getUniqueId(),player.getWorld().getName(),active,runtimes));}return List.copyOf(result);}
    private static List<LiveStateSnapshot.Behavior> behaviors(Main plugin,Filter filter){List<LiveStateSnapshot.Behavior> result=new ArrayList<>();for(var runtime:plugin.behaviors().runtimeSummaries()){
        UUID player=runtime.player().equals("shared")?null:uuid(runtime.player());if(player!=null&&!filter.player(player)||!filter.npc(runtime.npcDefinition(),runtime.instance()))continue;
        List<LiveStateSnapshot.Outcome> outcomes=runtime.trace().stream().map(value->new LiveStateSnapshot.Outcome(value.at(),value.node(),value.status().name(),value.detail())).toList();
        List<LiveStateSnapshot.Condition> conditions=runtime.trace().stream().filter(value->value.inputs()!=null&&!value.inputs().isEmpty()).map(value->new LiveStateSnapshot.Condition(value.at(),value.node(),value.inputs(),Objects.toString(value.output(),""),value.detail())).toList();
        List<LiveStateSnapshot.Event> inbox=new ArrayList<>();for(int i=0;i<runtime.events().size();i++){BehaviorEvent event=runtime.events().get(i);inbox.add(new LiveStateSnapshot.Event(event.id(),event.type(),event.occurredAt().toEpochMilli(),event.policy().name(),i==0));}
        String status=runtime.paused()?"PAUSED":!runtime.runningPath().isEmpty()?"RUNNING":runtime.wakeAt()>System.currentTimeMillis()?"SLEEPING":"IDLE";
        var navigation=runtime.navigation();result.add(new LiveStateSnapshot.Behavior(runtime.npcDefinition(),runtime.instance(),player,runtime.behavior(),runtime.treeHash(),status,runtime.runningPath(),runtime.checkpoint(),runtime.wakeAt(),runtime.deadlines(),outcomes,conditions,inbox,runtime.droppedEvents(),new LiveStateSnapshot.Navigation(navigation.target(),navigation.startedAt(),navigation.status(),navigation.result(),navigation.reason())));}
        return List.copyOf(result);}
    private static List<LiveStateSnapshot.Npc> npcs(Main plugin,Filter filter,List<LiveStateSnapshot.Behavior> behaviors){List<LiveStateSnapshot.Npc> result=new ArrayList<>();for(NPC npc:CitizensAPI.getNPCRegistry()){
        PersonaTrait trait=npc.getTraitNullable(PersonaTrait.class);if(trait==null||!trait.bound()||trait.projection())continue;String instance=Objects.toString(trait.instanceId(),npc.getUniqueId().toString());if(!filter.npc(trait.definitionId(),instance))continue;
        Location base=npc.getStoredLocation();var details=details(npc);if(base!=null&&filter.world(base.getWorld().getName()))result.add(new LiveStateSnapshot.Npc(trait.definitionId(),instance,npc.getId(),null,"shared",null,position(base),npc.isSpawned(),npc.isSpawned()?"spawned":"despawned",0,navigation(behaviors,trait.definitionId(),instance,null,npc.getNavigator().isNavigating()),details.name(),details.type(),details.skin(),details.equipment(),details.age(),details.pose()));
        for(Player player:plugin.getServer().getOnlinePlayers()){if(!filter.player(player.getUniqueId()))continue;var view=plugin.behaviors().projections().inspect(player,npc);Location shown=view.projection()!=null?view.projection().getStoredLocation():base;if(shown==null||!filter.world(shown.getWorld().getName()))continue;double distance=player.getWorld().equals(shown.getWorld())?player.getLocation().distance(shown):-1;var shownDetails=view.projection()==null?details:details(view.projection());result.add(new LiveStateSnapshot.Npc(trait.definitionId(),instance,npc.getId(),player.getUniqueId(),view.projection()==null?"shared":"private",view.anchor(),view.position()==null?position(shown):position(view.position()),view.visible(),view.reason(),distance,navigation(behaviors,trait.definitionId(),instance,player.getUniqueId(),view.projection()!=null&&view.projection().getNavigator().isNavigating()),shownDetails.name(),shownDetails.type(),shownDetails.skin(),shownDetails.equipment(),shownDetails.age(),shownDetails.pose()));}}
        return List.copyOf(result);}
    private static LiveStateSnapshot.Navigation navigation(List<LiveStateSnapshot.Behavior> behaviors,String definition,String instance,UUID player,boolean navigating){return behaviors.stream().filter(value->value.definitionId().equals(definition)&&value.instanceId().equals(instance)&&Objects.equals(value.playerId(),player)).map(LiveStateSnapshot.Behavior::navigation).filter(Objects::nonNull).filter(value->!value.status().equals("IDLE")).findFirst().orElseGet(()->new LiveStateSnapshot.Navigation("",0,navigating?"RUNNING":"IDLE","",""));}
    private static NpcDetails details(NPC npc){SkinTrait skin=npc.getTraitNullable(SkinTrait.class);Equipment equipment=npc.getTraitNullable(Equipment.class);Map<String,String> items=new TreeMap<>();if(equipment!=null)equipment.getEquipmentBySlot().forEach((slot,item)->{if(item!=null&&!item.getType().isAir())items.put(slot.name(),item.getType().getKey().toString());});var entity=npc.isSpawned()?npc.getEntity():null;return new NpcDetails(npc.getRawName(),npc.getCosmeticEntityType().getKey().toString(),skin==null?null:skin.getSkinName(),items,entity instanceof Ageable age?age.getAge():null,entity==null?null:entity.getPose().name());}
    private record NpcDetails(String name,String type,String skin,Map<String,String> equipment,Integer age,String pose){}
    private static List<LiveStateSnapshot.Quest> quests(Main plugin,Filter filter){List<LiveStateSnapshot.Quest> result=new ArrayList<>();for(PlayerState state:plugin.states().all()){if(!filter.player(state.playerId()))continue;for(var entry:state.quests().entrySet()){Content.Quest quest=plugin.registry().quests().get(entry.getKey());if(quest==null||entry.getValue().phase()<0||entry.getValue().phase()>=quest.phases().size())continue;Content.Phase phase=quest.phases().get(entry.getValue().phase());List<LiveStateSnapshot.Objective> objectives=new ArrayList<>();for(Content.Objective objective:phase.objectives()){var progress=entry.getValue().objectives().get(objective.id());long required=switch(objective.type()){case WAIT,SURVIVE->objective.duration().toMillis();case CUSTOM->objective.requiredProgress();default->objective.amount();};objectives.add(new LiveStateSnapshot.Objective(objective.id(),objective.type()==Content.ObjectiveType.CUSTOM?objective.extensionType():objective.type().name(),progress==null?0:progress.value(),required,objective.optional(),objective.hidden()));}Long deadline=quest.timeLimit()==null?null:entry.getValue().startedAt()+quest.timeLimit().toMillis();result.add(new LiveStateSnapshot.Quest(state.playerId(),quest.id(),phase.id(),objectives,deadline,state.completions().getOrDefault(quest.id(),0),plugin.quests().recentEvents(state.playerId(),quest.id())));}for(var completed:state.completions().entrySet())if(!state.quests().containsKey(completed.getKey()))result.add(new LiveStateSnapshot.Quest(state.playerId(),completed.getKey(),"completed",List.of(),null,completed.getValue(),plugin.quests().recentEvents(state.playerId(),completed.getKey())));}return List.copyOf(result);}
    private static List<LiveStateSnapshot.Dialogue> dialogues(Main plugin,Filter filter){return plugin.dialogues().liveSummaries().stream().filter(value->filter.player(value.playerId())&&filter.npc(value.npcDefinition(),value.npcInstance())).map(value->new LiveStateSnapshot.Dialogue(value.playerId(),value.dialogueId(),value.nodeId(),value.state(),value.npcDefinition(),value.npcInstance(),value.currentLine(),value.eligibleChoices(),value.waitDeadline(),value.cancellationReason())).toList();}
    private static List<LiveStateSnapshot.Memory> memories(Main plugin,Filter filter){Set<String> visible=new HashSet<>(plugin.getConfig().getStringList("editor.memory-visible-namespaces"));List<LiveStateSnapshot.Memory> result=new ArrayList<>();for(NpcMemoryService.Entry entry:plugin.memories().entries()){
        if(entry.player()!=null&&!filter.player(entry.player())||!filter.npc(entry.npcDefinition(),entry.instance()))continue;boolean reveal=visible.stream().anyMatch(prefix->entry.key().equals(prefix)||entry.key().startsWith(prefix+"."));String key=reveal?entry.key():"redacted:"+digest(entry.key()),value=reveal?String.valueOf(entry.value().value()):"<redacted>";var memory=entry.value();result.add(new LiveStateSnapshot.Memory(entry.player(),entry.npcDefinition(),entry.instance(),key,memory.type().name(),value,memory.createdAt().toEpochMilli(),memory.updatedAt().toEpochMilli(),memory.expiresAt()==null?null:memory.expiresAt().toEpochMilli(),memory.source(),entry.scope().name(),!reveal));}return List.copyOf(result);}
    private static List<LiveStateSnapshot.GraphTrace> traces(Main plugin,Filter filter){
        if(filter.requested().tracepoints().isEmpty()&&filter.requested().watchedPins().isEmpty())return List.of();
        List<LiveStateSnapshot.GraphTrace> result=new ArrayList<>();
        for(var trace:plugin.scripts().graphTraceHistory()){
            if(trace.player()!=null&&!filter.player(trace.player()))continue;
            if(trace.npcDefinition()!=null&&!filter.npc(trace.npcDefinition(),trace.npcInstance()))continue;
            String base=trace.graph().contains("#")?trace.graph().substring(0,trace.graph().indexOf('#')):trace.graph();
            String token=pinToken(trace.node());
            Set<String> owners=new LinkedHashSet<>(filter.requested().tracepoints());
            for(String watch:filter.requested().watchedPins()){int marker=watch.indexOf(":input:");if(marker<0)marker=watch.indexOf(":output:");if(marker>0)owners.add(watch.substring(0,marker));}
            String owner=owners.stream().filter(id->id.matches("(?:npc|dialogue|quest|script):"+java.util.regex.Pattern.quote(base)+"#.*")
                    &&id.endsWith(":node:"+token)).findFirst().orElse(null);
            if(owner==null)continue;
            Map<String,String> watched=new LinkedHashMap<>();
            for(String watch:filter.requested().watchedPins())if(watch.startsWith(owner+":")){
                String label=watch.substring(watch.lastIndexOf(':')+1);
                trace.values().forEach((pin,value)->{if(pinToken(pin).equals(label))watched.put(watch,value);});
            }
            if(!filter.requested().tracepoints().contains(owner)&&watched.isEmpty())continue;
            result.add(new LiveStateSnapshot.GraphTrace(trace.sequence(),trace.at(),trace.graph(),owner,trace.node(),trace.status(),
                    trace.player(),trace.npcInstance(),watched,trace.detail()));
        }
        return result.size()<=1000?List.copyOf(result):List.copyOf(result.subList(result.size()-1000,result.size()));
    }
    private static String pinToken(String value){return(value==null?"":value).replaceAll("[^A-Za-z0-9_.:-]","_");}
    private static LiveStateSnapshot.Position position(Location value){return new LiveStateSnapshot.Position(value.getWorld().getName(),value.getX(),value.getY(),value.getZ(),value.getYaw(),value.getPitch());}
    private static LiveStateSnapshot.Position position(BehaviorRuntime.LogicalPosition value){return new LiveStateSnapshot.Position(value.world(),value.x(),value.y(),value.z(),value.yaw(),value.pitch());}
    private static UUID uuid(String value){try{return UUID.fromString(value);}catch(IllegalArgumentException error){return null;}}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,12);}catch(Exception error){throw new IllegalStateException(error);}}
    private record Filter(LiveFilter requested,SessionRestrictions allowed){boolean player(UUID id){String text=id.toString();return(requested.playerIds().isEmpty()||requested.playerIds().contains(id))&&(allowed.playerIds().isEmpty()||allowed.playerIds().contains(text));}boolean world(String world){String value=world.toLowerCase(Locale.ROOT);return(requested.worlds().isEmpty()||requested.worlds().contains(value))&&(allowed.worlds().isEmpty()||allowed.worlds().contains(value));}boolean npc(String definition,String instance){boolean requestedOk=(requested.npcDefinitions().isEmpty()||requested.npcDefinitions().contains(definition))&&(requested.npcInstances().isEmpty()||requested.npcInstances().contains(instance));return requestedOk&&(allowed.npcIds().isEmpty()||allowed.npcIds().contains(definition)||allowed.npcIds().contains(instance));}}
}
