package nu.miguel.persona.state;

import nu.miguel.persona.Main;
import nu.miguel.persona.content.Content;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class StateManager {
    private final Main plugin;
    private final SqliteStore store;
    private final Map<UUID, PlayerState> loaded=new ConcurrentHashMap<>();
    private final Set<UUID> loading=ConcurrentHashMap.newKeySet();
    public StateManager(Main plugin,SqliteStore store){this.plugin=plugin;this.store=store;}
    public void load(Player player){
        UUID id=player.getUniqueId(); if(loaded.containsKey(id)||!loading.add(id))return;
        store.load(id).whenComplete((state,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
            loading.remove(id);
            if(error!=null){plugin.getLogger().severe("Could not load "+player.getName()+": "+error.getMessage());return;}
            if(!player.isOnline()){store.save(state);return;}
            loaded.put(id,state); logDormant(state,plugin.registry());
            plugin.quests().onLoaded(player);
        }));
    }
    private void logDormant(PlayerState state,Content.Registry registry){for(String id:state.quests().keySet())if(!registry.quests().containsKey(id))plugin.getLogger().warning("Retaining dormant quest "+id+" for "+state.playerId());}
    public Optional<PlayerState> get(UUID id){return Optional.ofNullable(loaded.get(id));}
    public PlayerState require(Player player){return loaded.get(player.getUniqueId());}
    public boolean ready(Player player){return loaded.containsKey(player.getUniqueId());}
    public void save(PlayerState state){store.save(state);}
    public void unload(Player player){PlayerState state=loaded.remove(player.getUniqueId());if(state!=null)store.save(state);loading.remove(player.getUniqueId());}
    public Collection<PlayerState> all(){return loaded.values();}
    public void saveAll(){loaded.values().forEach(store::save);}
}
