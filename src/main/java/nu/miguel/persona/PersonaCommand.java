package nu.miguel.persona;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import nu.miguel.persona.citizens.PersonaTrait;
import nu.miguel.persona.content.Content.*;
import nu.miguel.persona.state.PlayerState;
import nu.miguel.persona.api.NpcMemoryService;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.io.File;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import nu.miguel.persona.state.MemoryTimes;
import nu.miguel.persona.state.MemoryTransfer;
import nu.miguel.persona.editor.protocol.EditorScope;
import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.persona.editor.protocol.EditorSessionStatus;
import nu.miguel.persona.editor.protocol.SessionRestrictions;
import nu.miguel.persona.editor.EditorClient;

public final class PersonaCommand implements CommandExecutor,TabCompleter {
    private final Main plugin;
    public PersonaCommand(Main plugin){this.plugin=plugin;}
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull org.bukkit.command.Command command,@NotNull String label,String @NotNull [] args){
        if(args.length==0){sender.sendMessage(Component.text("/persona quests | quest show | dialogue cancel | npc | memory | behavior | editor | validate | debug | diagnostics | support | backup | reload"));return true;}
        if(args[0].equals("_choose")&&sender instanceof Player p&&args.length==2){plugin.dialogues().choose(p,args[1]);return true;}
        if(args[0].equalsIgnoreCase("quests")&&sender instanceof Player p){if(!perm(sender,"persona.player.quests"))return true;quests(p,args);return true;}
        if(args[0].equalsIgnoreCase("quest")){return quest(sender,args);}
        if(args[0].equalsIgnoreCase("dialogue")&&sender instanceof Player p&&args.length>1&&args[1].equalsIgnoreCase("cancel")){if(!perm(sender,"persona.player.dialogue.cancel"))return true;if(!plugin.dialogues().cancel(p.getUniqueId(),"Conversation cancelled."))sender.sendMessage(Component.text("No active conversation."));return true;}
        if(args[0].equalsIgnoreCase("npc")){npc(sender,args);return true;}
        if(args[0].equalsIgnoreCase("memory")){memory(sender,args);return true;}
        if(args[0].equalsIgnoreCase("behavior")){behavior(sender,args);return true;}
        if(args[0].equalsIgnoreCase("editor")){editor(sender,args);return true;}
        if(args[0].equalsIgnoreCase("validate")){validate(sender,args,false);return true;}
        if(args[0].equalsIgnoreCase("debug")){debug(sender,args);return true;}
        if(args[0].equalsIgnoreCase("diagnostics")){diagnostics(sender);return true;}
        if(args[0].equalsIgnoreCase("support")){support(sender);return true;}
        if(args[0].equalsIgnoreCase("backup")){backup(sender);return true;}
        if(args[0].equalsIgnoreCase("example")){example(sender,args);return true;}
        if(args[0].equalsIgnoreCase("reload")){if(!perm(sender,"persona.admin.reload"))return true;if(Arrays.stream(args).anyMatch(x->x.equalsIgnoreCase("--dry-run"))){validate(sender,args,true);return true;}boolean ok=plugin.reloadPersona();if(Arrays.stream(args).anyMatch(x->x.equalsIgnoreCase("--json")))sender.sendMessage(Component.text("{\"valid\":"+ok+",\"activated\":"+ok+"}"));else sender.sendMessage(Component.text(ok?"Persona content reloaded.":"Reload failed; previous content retained. See console."));return true;}
        return false;
    }
    private void quests(Player p,String[] args){PlayerState s=ready(p);if(s==null)return;int page=args.length>1?parse(args[1],1):1;List<String> ids=new ArrayList<>(s.quests().keySet());ids.sort(String::compareTo);int pages=Math.max(1,(ids.size()+7)/8);page=Math.max(1,Math.min(page,pages));p.sendMessage(Component.text("Active quests — page "+page+"/"+pages));for(String id:ids.subList((page-1)*8,Math.min(page*8,ids.size()))){Quest q=plugin.registry().quests().get(id);String title=q==null?id+" (content unavailable)":q.title();p.sendMessage(Component.text(" • "+title).clickEvent(ClickEvent.runCommand("/persona quest show "+id)));}Component nav=Component.empty();if(page>1)nav=nav.append(Component.text("[Previous] ").clickEvent(ClickEvent.runCommand("/persona quests "+(page-1))));if(page<pages)nav=nav.append(Component.text("[Next]").clickEvent(ClickEvent.runCommand("/persona quests "+(page+1))));p.sendMessage(nav);}
    private boolean quest(CommandSender sender,String[] args){if(args.length>=3&&args[1].equalsIgnoreCase("show")&&sender instanceof Player p){if(!perm(sender,"persona.player.quests"))return true;show(p,args[2]);return true;}if(args.length==4&&Set.of("start","finish","reset").contains(args[1].toLowerCase(Locale.ROOT))){if(!perm(sender,"persona.admin.quest"))return true;Player target=plugin.getServer().getPlayerExact(args[2]);if(target==null){sender.sendMessage(Component.text("Player must be online."));return true;}var result=switch(args[1].toLowerCase(Locale.ROOT)){case "start"->plugin.quests().start(target,args[3]);case "finish"->plugin.quests().finish(target,args[3]);default->plugin.quests().reset(target,args[3]);};sender.sendMessage(Component.text(result.message()));return true;}return false;}
    private void show(Player p,String id){PlayerState s=ready(p);if(s==null)return;Quest q=plugin.registry().quests().get(id);var qp=s.quests().get(id);if(q==null){p.sendMessage(Component.text("Quest content is unavailable (progress is retained)."));return;}if(qp==null){p.sendMessage(Component.text(q.title()+": "+s.questState(id)+(s.completions().getOrDefault(id,0)>0?" (completed "+s.completions().get(id)+"x)":"")));return;}Phase phase=q.phases().get(qp.phase());p.sendMessage(Component.text(q.title()+" — "+phase.title()));if(!phase.description().isBlank())p.sendMessage(Component.text(phase.description()));for(Objective o:phase.objectives()){if(o.hidden())continue;long current=qp.objectives().get(o.id()).value();long required=(o.type()==ObjectiveType.WAIT||o.type()==ObjectiveType.SURVIVE)?o.duration().toMillis():o.type()==ObjectiveType.CUSTOM?o.requiredProgress():o.amount();p.sendMessage(Component.text(" • "+o.title()+": "+formatProgress(o,current,required)+(o.optional()?" (optional)":"")));}}
    private String formatProgress(Objective o,long current,long required){if(o.type()!=ObjectiveType.WAIT&&o.type()!=ObjectiveType.SURVIVE)return current+"/"+required;return (current/1000)+"s/"+(required/1000)+"s";}
    private void npc(CommandSender sender,String[] args){if(!perm(sender,"persona.admin.npc"))return;NPC npc=CitizensAPI.getDefaultNPCSelector().getSelected(sender);if(npc==null){sender.sendMessage(Component.text("Select a Citizens NPC first."));return;}if(args.length>=2&&args[1].equalsIgnoreCase("bind")&&args.length>=3){if(!plugin.registry().npcs().containsKey(args[2])){sender.sendMessage(Component.text("Unknown NPC definition "+args[2]));return;}if(!plugin.behaviors().projections().validateBase(npc,true)){sender.sendMessage(Component.text("Binding rejected: remove the Citizens playerfilter trait first; Persona controls player visibility."));return;}npc.getOrAddTrait(PersonaTrait.class).bind(args[2],args.length>3?args[3]:null);sender.sendMessage(Component.text("Bound Citizens NPC "+npc.getId()+" to "+args[2]+"."));}else if(args.length>=2&&args[1].equalsIgnoreCase("unbind")){npc.removeTrait(PersonaTrait.class);sender.sendMessage(Component.text("Persona binding removed."));}else if(args.length>=2&&Set.of("info","trace").contains(args[1].toLowerCase(Locale.ROOT))){PersonaTrait t=npc.getTraitNullable(PersonaTrait.class);if(t==null||!t.bound()){sender.sendMessage(Component.text("NPC is not bound."));return;}sender.sendMessage(Component.text("Definition: "+t.definitionId()+", instance: "+Objects.toString(t.instanceId(),"(NPC UUID)")));plugin.behaviors().runtime(npc,null).ifPresent(r->runtimeLine(sender,"shared",r));if(sender instanceof Player p){plugin.behaviors().runtime(npc,p).ifPresent(r->runtimeLine(sender,"player",r));var view=plugin.behaviors().projections().inspect(p,npc);var counts=plugin.behaviors().projections().counts(p);sender.sendMessage(Component.text("Presentation: "+view.reason()+", anchor="+Objects.toString(view.anchor(),"shared")+", visible="+view.visible()));sender.sendMessage(Component.text("Projections: player "+counts.player()+"/"+counts.playerLimit()+", server "+counts.server()+"/"+counts.serverLimit()));}else{var counts=plugin.behaviors().projections().counts();sender.sendMessage(Component.text("Server projections: "+counts.server()+"/"+counts.serverLimit()));}}else sender.sendMessage(Component.text("/persona npc bind <npc-id> [instance-id] | unbind | info | trace"));}
    private void runtimeLine(CommandSender sender,String scope,nu.miguel.persona.behavior.BehaviorRuntime r){
        sender.sendMessage(Component.text(scope+": behavior="+r.behaviorId()+", tree="+r.treeHash()+", state="+(r.paused()?"paused":"active")));
        sender.sendMessage(Component.text("  running="+(r.runningPath().isEmpty()?"idle":String.join(" > ",r.runningPath()))+", checkpoint="+Objects.toString(r.checkpoint(),"none")+", wake="+(r.nextWakeAt()<=0?"none":Instant.ofEpochMilli(r.nextWakeAt()))));
        sender.sendMessage(Component.text("  presentation anchor="+Objects.toString(r.anchor(),"shared")+", position="+Objects.toString(r.position(),"inherited")+", visible="+r.visible()));
        sender.sendMessage(Component.text("  deadlines="+r.deadlines()+", progress="+r.progress()+", blackboard="+redacted(r.blackboard())));
        sender.sendMessage(Component.text("  inbox="+r.inbox().stream().map(e->e.id()+" "+e.type()+" @ "+e.occurredAt()+" "+redacted(e.data())).toList()+", dropped="+r.droppedEvents()));
        if(!r.traceHistory().isEmpty())for(var item:r.traceHistory())sender.sendMessage(Component.text("  outcome "+Instant.ofEpochMilli(item.at())+" "+item.node()+"="+item.status()+" inputs="+redacted(item.inputs())+" output="+Objects.toString(item.output(),"-")+(item.detail()==null?"":" — "+item.detail())));
    }
    private Map<String,Object> redacted(Map<String,?> values){Map<String,Object> result=new LinkedHashMap<>();values.forEach((key,value)->result.put(key,key.toLowerCase(Locale.ROOT).matches(".*(password|secret|token|key).*" )?"<redacted>":value));return result;}
    private record MemoryTarget(UUID player,String label,int cursor){}
    private void memory(CommandSender sender,String[] args){
        if(!perm(sender,"persona.admin.memory"))return;
        if(args.length<2){memoryUsage(sender);return;}
        String operation=args[1].toLowerCase(Locale.ROOT);
        if(Set.of("export","import","metrics").contains(operation)){memoryFiles(sender,args,operation);return;}
        if(args.length<3){memoryUsage(sender);return;}
        NPC npc=CitizensAPI.getDefaultNPCSelector().getSelected(sender);PersonaTrait trait=npc==null?null:npc.getTraitNullable(PersonaTrait.class);
        if(trait==null||!trait.bound()){sender.sendMessage(Component.text("Select a bound Citizens NPC first."));return;}
        try{
            MemoryTarget target=memoryTarget(sender,args,operation);boolean modifying=!Set.of("get","list").contains(operation);
            String permission=target.player()==null?(modifying?"persona.admin.memory.modify.global":"persona.admin.memory.inspect.global"):(modifying?"persona.admin.memory.modify.player":"persona.admin.memory.inspect.player");
            if(!perm(sender,permission))return;
            String instance=trait.instanceId()==null?npc.getUniqueId().toString():trait.instanceId(),source="admin:"+sender.getName();
            if(operation.equals("list")){memoryList(sender,target,trait.definitionId(),instance,args);return;}
            if(args.length<=target.cursor())throw new IllegalArgumentException(operation+" needs <key>");String key=args[target.cursor()];int value=target.cursor()+1;
            switch(operation){
                case "get"->sender.sendMessage(Component.text(plugin.memories().get(target.player(),trait.definitionId(),instance,key).map(v->memoryLine(key,v)).orElse(key+" is not set")));
                case "set"->{if(args.length<value+2)throw new IllegalArgumentException("set needs <type> <value> [expiry]");NpcMemoryService.Type type=type(args[value]);Object raw=memoryValue(type,args[value+1]);Duration ttl=args.length>value+2?MemoryTimes.ttl(args[value+2]):null;var result=plugin.memories().set(target.player(),trait.definitionId(),instance,key,type,raw,ttl,source);sender.sendMessage(Component.text("Set "+memoryLine(key,result)));}
                case "increment","adjust"->{if(args.length<=value)throw new IllegalArgumentException("adjust needs <amount> [minimum] [maximum] [expiry]");double delta=Double.parseDouble(args[value]);double min=args.length>value+1?Double.parseDouble(args[value+1]):-Double.MAX_VALUE,max=args.length>value+2?Double.parseDouble(args[value+2]):Double.MAX_VALUE;Duration ttl=args.length>value+3?MemoryTimes.ttl(args[value+3]):null;var result=plugin.memories().adjust(target.player(),trait.definitionId(),instance,key,delta,min,max,ttl,source);sender.sendMessage(Component.text("Set "+memoryLine(key,result)));}
                case "cas"->{if(args.length<value+3)throw new IllegalArgumentException("cas needs <type> <expected|unset> <value> [expiry]");NpcMemoryService.Type type=type(args[value]);Object expected=args[value+1].equalsIgnoreCase("unset")?null:memoryValue(type,args[value+1]),raw=memoryValue(type,args[value+2]);Duration ttl=args.length>value+3?MemoryTimes.ttl(args[value+3]):null;var result=plugin.memories().compareAndSet(target.player(),trait.definitionId(),instance,key,expected,type,raw,ttl,source);sender.sendMessage(Component.text(result.applied()?"Updated "+memoryLine(key,result.value()):"Not updated; current value is "+(result.value()==null?"unset":result.value().value())));}
                case "expire"->{if(args.length<=value)throw new IllegalArgumentException("expire needs <now|ISO-8601|duration>");Instant at=MemoryTimes.parse(args[value]);sender.sendMessage(Component.text(plugin.memories().expire(target.player(),trait.definitionId(),instance,key,at,source)?"Expiry set for "+key+" to "+at:key+" was not set"));}
                case "delete"->sender.sendMessage(Component.text(plugin.memories().forget(target.player(),trait.definitionId(),instance,key,source)?"Deleted "+key:key+" was not set"));
                default->throw new IllegalArgumentException("unknown operation");
            }
        }catch(RuntimeException e){sender.sendMessage(Component.text("Memory command failed: "+Objects.toString(e.getMessage(),e.getClass().getSimpleName())));}
    }
    private MemoryTarget memoryTarget(CommandSender sender,String[] args,String operation){
        if(args[2].equalsIgnoreCase("global"))return new MemoryTarget(null,"global",3);
        if(!args[2].equalsIgnoreCase("player"))throw new IllegalArgumentException("scope must be global or player");
        if(args.length<=3){if(sender instanceof Player p)return new MemoryTarget(p.getUniqueId(),p.getName(),3);throw new IllegalArgumentException("player scope needs <player|uuid>");}
        // Preserve the original self-targeting syntax where its shape is unambiguous.
        boolean legacy=(Set.of("get","delete").contains(operation)&&args.length==4)||(Set.of("increment","adjust").contains(operation)&&args.length==5)||(operation.equals("set")&&args.length>=6&&isType(args[4]));
        if(legacy&&sender instanceof Player p)return new MemoryTarget(p.getUniqueId(),p.getName(),3);
        String selector=args[3];if(selector.equalsIgnoreCase("self")&&sender instanceof Player p)return new MemoryTarget(p.getUniqueId(),p.getName(),4);
        OfflinePlayer player;boolean uuid=true;try{player=plugin.getServer().getOfflinePlayer(UUID.fromString(selector));}catch(IllegalArgumentException ignored){uuid=false;player=plugin.getServer().getOfflinePlayer(selector);}
        if(!uuid&&!player.isOnline()&&!player.hasPlayedBefore())throw new IllegalArgumentException("unknown player "+selector+"; use a UUID for migrated/offline players");
        return new MemoryTarget(player.getUniqueId(),Objects.toString(player.getName(),player.getUniqueId().toString()),4);
    }
    private void memoryList(CommandSender sender,MemoryTarget target,String npc,String instance,String[] args){
        int page=args.length>target.cursor()?Math.max(1,parse(args[target.cursor()],1)):1,size=8;var entries=new ArrayList<>(plugin.memories().entries(target.player(),npc,instance).entrySet());int pages=Math.max(1,(entries.size()+size-1)/size);page=Math.min(page,pages);
        sender.sendMessage(Component.text("Memory for "+target.label()+" — page "+page+"/"+pages));for(var entry:entries.subList((page-1)*size,Math.min(page*size,entries.size())))sender.sendMessage(Component.text(" • "+memoryLine(entry.getKey(),entry.getValue())));
    }
    private String memoryLine(String key,NpcMemoryService.Value value){return key+"="+value.value()+" ["+value.type()+", source="+Objects.toString(value.source(),"unknown")+", updated="+value.updatedAt()+(value.expiresAt()==null?"":", expires="+value.expiresAt())+"]";}
    private Object memoryValue(NpcMemoryService.Type type,String raw){return type==NpcMemoryService.Type.TIMESTAMP?MemoryTimes.parse(raw):raw;}
    private NpcMemoryService.Type type(String raw){return NpcMemoryService.Type.valueOf(raw.toUpperCase(Locale.ROOT));}
    private boolean isType(String raw){try{type(raw);return true;}catch(RuntimeException e){return false;}}
    private void memoryFiles(CommandSender sender,String[] args,String operation){
        if(operation.equals("metrics")){if(!perm(sender,"persona.admin.memory.inspect.global"))return;var m=plugin.memories().sweepMetrics();sender.sendMessage(Component.text("Memory expiry sweeps: runs="+m.runs()+", rows="+m.rowsRemoved()+", last="+m.lastRowsRemoved()+" at "+(m.lastRunEpochMillis()==0?"never":Instant.ofEpochMilli(m.lastRunEpochMillis()))));return;}
        if(!perm(sender,"persona.admin.memory.migrate"))return;String name=args.length>2?args[2]:"memories.yml";File root=new File(plugin.getDataFolder(),"memory-transfer").getAbsoluteFile(),file=new File(root,name).getAbsoluteFile();
        try{if(!file.toPath().normalize().startsWith(root.toPath().normalize()))throw new IllegalArgumentException("file must stay inside memory-transfer");if(operation.equals("export")){int count=MemoryTransfer.exportTo(plugin.memories(),file);sender.sendMessage(Component.text("Exported "+count+" memories to "+file.getAbsolutePath()));}else{if(!file.isFile())throw new IllegalArgumentException("file does not exist");int count=MemoryTransfer.importFrom(plugin.memories(),file);sender.sendMessage(Component.text("Imported "+count+" memories from "+file.getAbsolutePath()));}}catch(Exception e){sender.sendMessage(Component.text("Memory "+operation+" failed: "+e.getMessage()));}
    }
    private void memoryUsage(CommandSender sender){sender.sendMessage(Component.text("/persona memory <get|list|set|adjust|cas|expire|delete> <global|player> [player|uuid] [arguments] | export|import|metrics"));}
    private void behavior(CommandSender sender,String[] args){if(!perm(sender,"persona.admin.behavior"))return;if(args.length<2){sender.sendMessage(Component.text("/persona behavior <pause|resume|restart|wake|signal> [name]"));return;}NPC npc=CitizensAPI.getDefaultNPCSelector().getSelected(sender);if(npc==null){sender.sendMessage(Component.text("Select a Citizens NPC first."));return;}String operation=args[1].toLowerCase(Locale.ROOT);if(operation.equals("signal")){if(args.length<3){sender.sendMessage(Component.text("/persona behavior signal <name>"));return;}String name=args[2].toLowerCase(Locale.ROOT);if(!name.matches("[a-z0-9][a-z0-9_.:-]*")){sender.sendMessage(Component.text("Invalid signal name."));return;}int count=plugin.behaviors().signalSelected(npc,sender instanceof Player p?p:null,name,Map.of("sender",sender.getName()));sender.sendMessage(Component.text("Sent signal:"+name+" to "+count+" runtime(s)."));return;}if(!Set.of("pause","resume","restart","wake").contains(operation)){sender.sendMessage(Component.text("Unknown behavior operation."));return;}int count=plugin.behaviors().controlSelected(npc,sender instanceof Player p?p:null,operation);sender.sendMessage(Component.text(Character.toUpperCase(operation.charAt(0))+operation.substring(1)+" applied to "+count+" runtime(s)."));}
    private void editor(CommandSender sender,String[] args){
        if(!perm(sender,"persona.admin.editor.open"))return;
        if(!perm(sender,"persona.admin.editor.view"))return;
        if(plugin.editor()==null){sender.sendMessage(Component.text("The hosted Persona editor is unavailable: "+Objects.toString(plugin.editorError(),"unknown reason")+"."));return;}
        if(args.length>1&&Set.of("sessions","trust","revoke","close","apply","rollback").contains(args[1].toLowerCase(Locale.ROOT))){editorSession(sender,args);return;}
        EditorScope scope;
        try{scope=EditorScope.parse(args.length>1?args[1]:"all");}catch(IllegalArgumentException e){sender.sendMessage(Component.text("Unknown editor scope. Use all, content, behaviors, npcs, dialogues, quests, or scripts."));return;}
        SessionRestrictions restrictions;
        try{restrictions=editorRestrictions(args,2);}catch(IllegalArgumentException e){sender.sendMessage(Component.text(e.getMessage()));return;}
        sender.sendMessage(Component.text("Creating a secure Persona editor session..."));
        plugin.editor().open(sender,scope,restrictions).whenComplete((session,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
            if(error!=null){Throwable cause=error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null?error.getCause():error;sender.sendMessage(Component.text("Could not open the editor: "+Objects.toString(cause.getMessage(),cause.getClass().getSimpleName())));return;}
            sender.sendMessage(Component.text("Open Persona Editor").clickEvent(ClickEvent.openUrl(session.editorUrl())));
            sender.sendMessage(Component.text("Session: "+session.sessionId()+"; verification code: "+session.verificationCode()));
            sender.sendMessage(Component.text("Scope: "+scope.name().toLowerCase(Locale.ROOT)+"; filters: "+restrictionText(restrictions)+"; requested: "+capabilities(EditorClient.requestedCapabilities(sender))+"; expires: "+session.expiresAt()));
            sender.sendMessage(Component.text("The verified browser starts read-only. Use /persona editor trust "+shortId(session.sessionId())+" to review it before granting elevated capabilities."));
        }));
    }
    private void editorSession(CommandSender sender,String[] args){
        String operation=args[1].toLowerCase(Locale.ROOT);
        if(operation.equals("sessions")){
            Collection<EditorClient.LocalSession> sessions=plugin.editor().sessions();
            sender.sendMessage(Component.text("Active Persona editor sessions: "+sessions.size()));
            sessions.forEach(session->sender.sendMessage(Component.text(" • "+shortId(session.id())+" "+session.initiatorName()+" scope="+session.scope().name().toLowerCase(Locale.ROOT)+" filters="+restrictionText(session.restrictions())+" requested="+capabilities(session.requestedCapabilities())+" expires="+session.expiresAt())));
            return;
        }
        if(args.length<3){sender.sendMessage(Component.text("/persona editor <trust|revoke|close> <session-id> [confirm] | apply <session-id> <code> | rollback <session-id> <publish-id> confirm"));return;}
        String reference=args[2];
        try{
            if(operation.equals("apply")){
                if(!perm(sender,"persona.admin.editor.publish"))return;
                if(!plugin.getConfig().getBoolean("editor.publish-enabled",false)){sender.sendMessage(Component.text("Hosted content publication is disabled in config.yml."));return;}
                if(args.length!=4){sender.sendMessage(Component.text("/persona editor apply <session-id> <one-time-code>"));return;}
                if(!plugin.editor().ownedBy(reference,sender)){sender.sendMessage(Component.text("Only the player or console that opened this session may confirm its publication."));return;}
                sender.sendMessage(Component.text("Revalidating and applying the confirmed editor candidate..."));
                plugin.editor().apply(reference,args[3]).whenComplete((result,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
                    if(error!=null){editorError(sender,error);return;}
                    sender.sendMessage(Component.text(result.status().equals("PUBLISHED")
                            ?"Publication applied. Active revision: "+result.activeRevision()+"; backup: "+result.backupId()
                            :"Publication failed with status "+result.status()+": "+Objects.toString(result.error(),"see audit log")));
                }));return;
            }
            if(operation.equals("rollback")){
                if(!perm(sender,"persona.admin.editor.publish"))return;
                if(!plugin.getConfig().getBoolean("editor.publish-enabled",false)){sender.sendMessage(Component.text("Hosted content publication is disabled in config.yml."));return;}
                if(args.length!=5||!args[4].equalsIgnoreCase("confirm")){sender.sendMessage(Component.text("/persona editor rollback <session-id> <publish-id> confirm"));return;}
                if(!plugin.editor().ownedBy(reference,sender)){sender.sendMessage(Component.text("Only the player or console that opened this session may confirm its rollback."));return;}
                UUID publishId;try{publishId=UUID.fromString(args[3]);}catch(IllegalArgumentException invalid){sender.sendMessage(Component.text("Publish ID must be a complete UUID."));return;}
                sender.sendMessage(Component.text("Revalidating and restoring the confirmed publication backup..."));
                plugin.editor().rollback(reference,publishId).whenComplete((result,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
                    if(error!=null){editorError(sender,error);return;}
                    sender.sendMessage(Component.text(result.status().equals("ROLLED_BACK")
                            ?"Rollback applied. Active revision: "+result.activeRevision()+"; safety backup: "+result.backupId()
                            :"Rollback failed with status "+result.status()+": "+Objects.toString(result.error(),"see audit log")));
                }));return;
            }
            if(operation.equals("close")){plugin.editor().close(reference);sender.sendMessage(Component.text("Editor session closed."));return;}
            if(operation.equals("revoke")){editorResult(sender,plugin.editor().revokeTrust(reference),"Editor trust revoked; the browser is read-only.");return;}
            if(!operation.equals("trust")){sender.sendMessage(Component.text("Unknown editor session operation."));return;}
            if(args.length<4||!args[3].equalsIgnoreCase("confirm")){
                plugin.editor().status(reference).whenComplete((status,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
                    if(error!=null){editorError(sender,error);return;}
                    showTrust(sender,status);
                    sender.sendMessage(Component.text("Run /persona editor trust "+shortId(status.sessionId())+" confirm to grant capabilities currently allowed by your permissions."));
                }));
                return;
            }
            Set<Capability> approved=EditorClient.requestedCapabilities(sender);
            editorResult(sender,plugin.editor().trust(reference,approved),"Editor capabilities trusted.");
        }catch(RuntimeException error){editorError(sender,error);}
    }
    private void showTrust(CommandSender sender,EditorSessionStatus status){
        sender.sendMessage(Component.text("Session "+status.sessionId()+" for "+status.initiatorName()+" ("+status.initiatorId()+")"));
        String code=plugin.editor().sessions().stream().filter(local->local.id().equals(status.sessionId())).map(EditorClient.LocalSession::verificationCode).findFirst().orElse("unavailable");
        sender.sendMessage(Component.text("Verification code: "+code+"; browser: "+Objects.toString(status.browserDescription(),"not verified")+"; verified="+status.browserVerified()));
        sender.sendMessage(Component.text("Scope: "+status.scope().name().toLowerCase(Locale.ROOT)+"; filters: "+restrictionText(status.restrictions())+"; requested: "+capabilities(status.requestedCapabilities())+"; granted: "+capabilities(status.grantedCapabilities())+"; expires: "+status.expiresAt()));
    }
    private static SessionRestrictions editorRestrictions(String[] args,int start){
        Set<String> worlds=new LinkedHashSet<>(),players=new LinkedHashSet<>(),npcs=new LinkedHashSet<>(),namespaces=new LinkedHashSet<>();
        for(int i=start;i<args.length;i++){
            String[] pair=args[i].split("=",2);
            if(pair.length!=2||pair[1].isBlank())throw new IllegalArgumentException("Editor filters use world=<id>, player=<uuid>, npc=<id>, or namespace=<id>.");
            Set<String> target=switch(pair[0].toLowerCase(Locale.ROOT)){case "world"->worlds;case "player"->players;case "npc"->npcs;case "namespace"->namespaces;default->throw new IllegalArgumentException("Unknown editor filter "+pair[0]+".");};
            target.addAll(Arrays.stream(pair[1].split(",")).map(String::trim).filter(value->!value.isEmpty()).toList());
        }
        return new SessionRestrictions(worlds,players,npcs,namespaces);
    }
    private static String restrictionText(SessionRestrictions restrictions){return restrictions.unrestricted()?"unrestricted":restrictions.signingValue();}
    private void editorResult(CommandSender sender,java.util.concurrent.CompletableFuture<EditorSessionStatus> future,String success){
        future.whenComplete((status,error)->plugin.getServer().getScheduler().runTask(plugin,()->{if(error!=null){editorError(sender,error);return;}sender.sendMessage(Component.text(success));showTrust(sender,status);}));
    }
    private void editorError(CommandSender sender,Throwable error){Throwable cause=error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null?error.getCause():error;sender.sendMessage(Component.text("Editor session operation failed: "+Objects.toString(cause.getMessage(),cause.getClass().getSimpleName())));}
    private static String shortId(UUID id){return id.toString().substring(0,8);}
    private static String capabilities(Collection<Capability> values){return values.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(", "));}
    private void validate(CommandSender sender,String[] args,boolean dryRun){if(!dryRun&&!perm(sender,"persona.admin.validate"))return;var report=plugin.validateContent();boolean json=Arrays.stream(args).anyMatch(x->x.equalsIgnoreCase("--json"));if(json){String value=report.json();sender.sendMessage(Component.text(value.substring(0,value.length()-1)+",\"activated\":false}"));return;}sender.sendMessage(Component.text((dryRun?"Reload dry-run":"Validation")+(report.valid()?" succeeded; no content was activated.":" failed with "+report.errors().size()+" error(s); active content was unchanged.")));report.errors().forEach(error->sender.sendMessage(Component.text(" • "+error)));}
    private void debug(CommandSender sender,String[] args){if(!perm(sender,"persona.admin.debug"))return;if(args.length<2||args[1].equalsIgnoreCase("off")){plugin.behaviors().debug(null);sender.sendMessage(Component.text("Scoped behavior debug logging disabled."));return;}Map<String,String> values=new HashMap<>();for(int i=1;i<args.length;i++){int split=args[i].indexOf('=');if(split>0)values.put(args[i].substring(0,split).toLowerCase(Locale.ROOT),args[i].substring(split+1));}var filter=new nu.miguel.persona.behavior.BehaviorService.DebugFilter(values.get("npc"),values.get("player"),values.get("behavior"),values.get("node"));plugin.behaviors().debug(filter);sender.sendMessage(Component.text("Scoped behavior debug logging: "+filter));}
    private void diagnostics(CommandSender sender){if(!perm(sender,"persona.admin.debug"))return;var orphans=plugin.behaviors().orphanedRuntimes();sender.sendMessage(Component.text("Orphaned persisted runtimes: "+orphans.size()));orphans.forEach(item->sender.sendMessage(Component.text(" • "+item)));plugin.behaviors().extensionUsage().forEach((name,usage)->sender.sendMessage(Component.text("Extension "+name+": calls="+usage.calls()+", total-ms="+String.format(Locale.ROOT,"%.3f",usage.totalMillis())+", max-ms="+String.format(Locale.ROOT,"%.3f",usage.maximumMillis()))));}
    private void support(CommandSender sender){if(!perm(sender,"persona.admin.support"))return;try{File file=SupportBundle.create(plugin,plugin.validateContent());sender.sendMessage(Component.text("Support bundle created: "+file.getAbsolutePath()+" (configuration secrets and runtime values are redacted)."));}catch(Exception e){sender.sendMessage(Component.text("Support bundle failed: "+e.getMessage()));}}
    private void backup(CommandSender sender){if(!perm(sender,"persona.admin.backup"))return;plugin.states().saveAll();plugin.memories().flush();plugin.behaviors().flush(false);File target=new File(plugin.getDataFolder(),"backups/persona-"+DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())+".db");sender.sendMessage(Component.text("Creating Persona database backup..."));plugin.store().backup(target).whenComplete((file,error)->plugin.getServer().getScheduler().runTask(plugin,()->sender.sendMessage(Component.text(error==null?"Backup created: "+file.getAbsolutePath():"Backup failed: "+error.getMessage()))));}
    private void example(CommandSender sender,String[] args){if(!perm(sender,"persona.admin.reload"))return;if(args.length<2||args[1].equalsIgnoreCase("list")){sender.sendMessage(Component.text("Packaged examples: "+String.join(", ",nu.miguel.persona.content.ExampleInstaller.AVAILABLE)));sender.sendMessage(Component.text("Use /persona example copy <path>; existing files are never overwritten."));return;}if(args.length!=3||!args[1].equalsIgnoreCase("copy")){sender.sendMessage(Component.text("/persona example list | copy <path>"));return;}try{var target=nu.miguel.persona.content.ExampleInstaller.copy(plugin.getDataFolder().toPath(),args[2]);sender.sendMessage(Component.text("Copied example to "+target+". Review it, then run /persona reload --dry-run."));}catch(Exception error){sender.sendMessage(Component.text("Example copy failed: "+error.getMessage()));}}
    private PlayerState ready(Player p){PlayerState s=plugin.states().require(p);if(s==null)p.sendMessage(Component.text("Your Persona data is still loading."));return s;}
    private boolean perm(CommandSender s,String permission){if(s.hasPermission(permission))return true;s.sendMessage(Component.text("You do not have permission."));return false;}
    private int parse(String v,int fallback){try{return Integer.parseInt(v);}catch(NumberFormatException e){return fallback;}}
    @Override public List<String> onTabComplete(@NotNull CommandSender sender,@NotNull org.bukkit.command.Command command,@NotNull String alias,String @NotNull [] args){if(args.length==1)return prefix(args[0],List.of("quests","quest","dialogue","npc","memory","behavior","editor","example","validate","debug","diagnostics","support","backup","reload"));if(args.length==2&&args[0].equalsIgnoreCase("example"))return prefix(args[1],List.of("list","copy"));if(args.length==3&&args[0].equalsIgnoreCase("example")&&args[1].equalsIgnoreCase("copy"))return prefix(args[2],nu.miguel.persona.content.ExampleInstaller.AVAILABLE);if(args.length==2&&args[0].equalsIgnoreCase("editor"))return prefix(args[1],List.of("all","content","behaviors","npcs","dialogues","quests","scripts","sessions","trust","revoke","close","apply","rollback"));if(args.length>=3&&args[0].equalsIgnoreCase("editor")&&!Set.of("trust","revoke","close","apply","rollback","sessions").contains(args[1].toLowerCase(Locale.ROOT)))return prefix(args[args.length-1],List.of("world=","player=","npc=","namespace="));if(args.length==3&&args[0].equalsIgnoreCase("editor")&&Set.of("trust","revoke","close","apply","rollback").contains(args[1].toLowerCase(Locale.ROOT)))return prefix(args[2],plugin.editor()==null?List.of():plugin.editor().sessions().stream().map(session->shortId(session.id())).toList());if(args.length==4&&args[0].equalsIgnoreCase("editor")&&args[1].equalsIgnoreCase("trust"))return prefix(args[3],List.of("confirm"));if(args.length==5&&args[0].equalsIgnoreCase("editor")&&args[1].equalsIgnoreCase("rollback"))return prefix(args[4],List.of("confirm"));if(args.length==2&&args[0].equalsIgnoreCase("quest"))return prefix(args[1],List.of("show","start","finish","reset"));if(args.length==2&&args[0].equalsIgnoreCase("behavior"))return prefix(args[1],List.of("pause","resume","restart","signal","wake"));if(args.length==2&&args[0].equalsIgnoreCase("reload"))return prefix(args[1],List.of("--dry-run","--json"));if(args.length==2&&args[0].equalsIgnoreCase("validate"))return prefix(args[1],List.of("--json"));if(args.length==2&&args[0].equalsIgnoreCase("debug"))return prefix(args[1],List.of("off","npc=","player=","behavior=","node="));if(args.length==2&&args[0].equalsIgnoreCase("npc"))return prefix(args[1],List.of("bind","unbind","info","trace"));if(args.length==2&&args[0].equalsIgnoreCase("memory"))return prefix(args[1],List.of("get","list","set","adjust","cas","expire","delete","export","import","metrics"));if(args.length==3&&args[0].equalsIgnoreCase("memory")&&!Set.of("export","import","metrics").contains(args[1].toLowerCase(Locale.ROOT)))return prefix(args[2],List.of("global","player"));if(args.length==4&&args[0].equalsIgnoreCase("memory")&&args[2].equalsIgnoreCase("player")){List<String> players=new ArrayList<>(plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList());players.add("self");return prefix(args[3],players);}if(args.length==3&&args[0].equalsIgnoreCase("npc")&&args[1].equalsIgnoreCase("bind"))return prefix(args[2],plugin.registry().npcs().keySet());return List.of();}
    private List<String> prefix(String p,Collection<String> values){return values.stream().filter(x->x.startsWith(p.toLowerCase(Locale.ROOT))).sorted().toList();}
}
