package nu.miguel.persona;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import nu.miguel.persona.citizens.PersonaTrait;
import nu.miguel.persona.content.Content.*;
import nu.miguel.persona.state.PlayerState;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class PersonaCommand implements CommandExecutor,TabCompleter {
    private final Main plugin;
    public PersonaCommand(Main plugin){this.plugin=plugin;}
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull org.bukkit.command.Command command,@NotNull String label,String @NotNull [] args){
        if(args.length==0){sender.sendMessage(Component.text("/persona quests | quest show | dialogue cancel | npc | reload"));return true;}
        if(args[0].equals("_choose")&&sender instanceof Player p&&args.length==2){plugin.dialogues().choose(p,args[1]);return true;}
        if(args[0].equalsIgnoreCase("quests")&&sender instanceof Player p){if(!perm(sender,"persona.player.quests"))return true;quests(p,args);return true;}
        if(args[0].equalsIgnoreCase("quest")){return quest(sender,args);}
        if(args[0].equalsIgnoreCase("dialogue")&&sender instanceof Player p&&args.length>1&&args[1].equalsIgnoreCase("cancel")){if(!perm(sender,"persona.player.dialogue.cancel"))return true;if(!plugin.dialogues().cancel(p.getUniqueId(),"Conversation cancelled."))sender.sendMessage(Component.text("No active conversation."));return true;}
        if(args[0].equalsIgnoreCase("npc")){npc(sender,args);return true;}
        if(args[0].equalsIgnoreCase("reload")){if(!perm(sender,"persona.admin.reload"))return true;boolean ok=plugin.reloadPersona();sender.sendMessage(Component.text(ok?"Persona content reloaded.":"Reload failed; previous content retained. See console."));return true;}
        return false;
    }
    private void quests(Player p,String[] args){PlayerState s=ready(p);if(s==null)return;int page=args.length>1?parse(args[1],1):1;List<String> ids=new ArrayList<>(s.quests().keySet());ids.sort(String::compareTo);int pages=Math.max(1,(ids.size()+7)/8);page=Math.max(1,Math.min(page,pages));p.sendMessage(Component.text("Active quests — page "+page+"/"+pages));for(String id:ids.subList((page-1)*8,Math.min(page*8,ids.size()))){Quest q=plugin.registry().quests().get(id);String title=q==null?id+" (content unavailable)":q.title();p.sendMessage(Component.text(" • "+title).clickEvent(ClickEvent.runCommand("/persona quest show "+id)));}Component nav=Component.empty();if(page>1)nav=nav.append(Component.text("[Previous] ").clickEvent(ClickEvent.runCommand("/persona quests "+(page-1))));if(page<pages)nav=nav.append(Component.text("[Next]").clickEvent(ClickEvent.runCommand("/persona quests "+(page+1))));p.sendMessage(nav);}
    private boolean quest(CommandSender sender,String[] args){if(args.length>=3&&args[1].equalsIgnoreCase("show")&&sender instanceof Player p){if(!perm(sender,"persona.player.quests"))return true;show(p,args[2]);return true;}if(args.length==4&&Set.of("start","finish","reset").contains(args[1].toLowerCase(Locale.ROOT))){if(!perm(sender,"persona.admin.quest"))return true;Player target=plugin.getServer().getPlayerExact(args[2]);if(target==null){sender.sendMessage(Component.text("Player must be online."));return true;}var result=switch(args[1].toLowerCase(Locale.ROOT)){case "start"->plugin.quests().start(target,args[3]);case "finish"->plugin.quests().finish(target,args[3]);default->plugin.quests().reset(target,args[3]);};sender.sendMessage(Component.text(result.message()));return true;}return false;}
    private void show(Player p,String id){PlayerState s=ready(p);if(s==null)return;Quest q=plugin.registry().quests().get(id);var qp=s.quests().get(id);if(q==null){p.sendMessage(Component.text("Quest content is unavailable (progress is retained)."));return;}if(qp==null){p.sendMessage(Component.text(q.title()+": "+s.questState(id)+(s.completions().getOrDefault(id,0)>0?" (completed "+s.completions().get(id)+"x)":"")));return;}Phase phase=q.phases().get(qp.phase());p.sendMessage(Component.text(q.title()+" — "+phase.title()));if(!phase.description().isBlank())p.sendMessage(Component.text(phase.description()));for(Objective o:phase.objectives()){if(o.hidden())continue;long current=qp.objectives().get(o.id()).value();long required=(o.type()==ObjectiveType.WAIT||o.type()==ObjectiveType.SURVIVE)?o.duration().toMillis():o.type()==ObjectiveType.CUSTOM?o.requiredProgress():o.amount();p.sendMessage(Component.text(" • "+o.title()+": "+formatProgress(o,current,required)+(o.optional()?" (optional)":"")));}}
    private String formatProgress(Objective o,long current,long required){if(o.type()!=ObjectiveType.WAIT&&o.type()!=ObjectiveType.SURVIVE)return current+"/"+required;return (current/1000)+"s/"+(required/1000)+"s";}
    private void npc(CommandSender sender,String[] args){if(!perm(sender,"persona.admin.npc"))return;NPC npc=CitizensAPI.getDefaultNPCSelector().getSelected(sender);if(npc==null){sender.sendMessage(Component.text("Select a Citizens NPC first."));return;}if(args.length>=2&&args[1].equalsIgnoreCase("bind")&&args.length>=3){if(!plugin.registry().npcs().containsKey(args[2])){sender.sendMessage(Component.text("Unknown NPC definition "+args[2]));return;}npc.getOrAddTrait(PersonaTrait.class).bind(args[2],args.length>3?args[3]:null);sender.sendMessage(Component.text("Bound Citizens NPC "+npc.getId()+" to "+args[2]+"."));}else if(args.length>=2&&args[1].equalsIgnoreCase("unbind")){npc.removeTrait(PersonaTrait.class);sender.sendMessage(Component.text("Persona binding removed."));}else if(args.length>=2&&args[1].equalsIgnoreCase("info")){PersonaTrait t=npc.getTraitNullable(PersonaTrait.class);sender.sendMessage(Component.text(t==null||!t.bound()?"NPC is not bound.":"Definition: "+t.definitionId()+", instance: "+Objects.toString(t.instanceId(),"(none)")));}else sender.sendMessage(Component.text("/persona npc bind <npc-id> [instance-id] | unbind | info"));}
    private PlayerState ready(Player p){PlayerState s=plugin.states().require(p);if(s==null)p.sendMessage(Component.text("Your Persona data is still loading."));return s;}
    private boolean perm(CommandSender s,String permission){if(s.hasPermission(permission))return true;s.sendMessage(Component.text("You do not have permission."));return false;}
    private int parse(String v,int fallback){try{return Integer.parseInt(v);}catch(NumberFormatException e){return fallback;}}
    @Override public List<String> onTabComplete(@NotNull CommandSender sender,@NotNull org.bukkit.command.Command command,@NotNull String alias,String @NotNull [] args){if(args.length==1)return prefix(args[0],List.of("quests","quest","dialogue","npc","reload"));if(args.length==2&&args[0].equalsIgnoreCase("quest"))return prefix(args[1],List.of("show","start","finish","reset"));if(args.length==2&&args[0].equalsIgnoreCase("npc"))return prefix(args[1],List.of("bind","unbind","info"));if(args.length==3&&args[0].equalsIgnoreCase("npc")&&args[1].equalsIgnoreCase("bind"))return prefix(args[2],plugin.registry().npcs().keySet());return List.of();}
    private List<String> prefix(String p,Collection<String> values){return values.stream().filter(x->x.startsWith(p.toLowerCase(Locale.ROOT))).sorted().toList();}
}
