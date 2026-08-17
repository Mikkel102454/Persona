package nu.miguel.persona;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.TraitInfo;
import nu.miguel.persona.citizens.PersonaTrait;
import nu.miguel.persona.citizens.ProjectionManager;
import nu.miguel.persona.behavior.BehaviorService;
import nu.miguel.persona.content.*;
import nu.miguel.persona.dialogue.DialogueService;
import nu.miguel.persona.quest.QuestService;
import nu.miguel.persona.script.EffectExecutor;
import nu.miguel.persona.script.ScriptEngine;
import nu.miguel.persona.state.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import nu.miguel.persona.api.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import nu.miguel.persona.api.event.NpcMemoryChangeEvent;

public final class Main extends JavaPlugin {

    private final AtomicContentRegistry registry=new AtomicContentRegistry(Content.Registry.empty());
    private SqliteStore store;
    private StateManager states;
    private QuestService quests;
    private DialogueService dialogues;
    private EffectExecutor effects;
    private ScriptEngine scripts;
    private PersonaApi api;
    private StandaloneExtensionLoader extensionLoader;
    private PersistentNpcMemoryService memories;
    private BehaviorService behaviors;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveExample("examples/npcs/builder.yml.example");saveExample("examples/dialogues/builder.yml.example");saveExample("examples/dialogues/builder_delivery.yml.example");saveExample("examples/dialogues/builder_thanks.yml.example");saveExample("examples/quests/supplies.yml.example");
        saveExample("examples/npcs/trial_master.yml.example");saveExample("examples/dialogues/trial_intro.yml.example");saveExample("examples/dialogues/trial_active.yml.example");saveExample("examples/dialogues/trial_complete.yml.example");saveExample("examples/quests/adventurers_trial.yml.example");
        saveExample("examples/scripts.yml.example");
        saveExample("examples/behaviors/builder-routine.yml.example");
        saveExample("examples/npcs/harbor_keeper.yml.example");
        saveExample("examples/behaviors/keeper-shared.yml.example");
        saveExample("examples/behaviors/keeper-player.yml.example");
        saveExample("examples/behaviors/keeper-festival.yml.example");
        saveExample("examples/npcs/private-walker.yml.example");
        saveExample("examples/behaviors/private-walker.yml.example");
        api=new PersonaApi(this);new BuiltinExpansion().register();extensionLoader=new StandaloneExtensionLoader(this);extensionLoader.loadAll();
        try {store=new SqliteStore(new File(getDataFolder(),"persona.db"),getLogger());}
        catch(SQLException e){getLogger().severe("Could not open persona.db: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        states=new StateManager(this,store);effects=new EffectExecutor(this);quests=new QuestService(this);scripts=new ScriptEngine(this);dialogues=new DialogueService(this);memories=new PersistentNpcMemoryService(store);configureMemories();behaviors=new BehaviorService(this,memories,new ProjectionManager(this));
        memories.onChange(c->getServer().getScheduler().runTask(this,()->{behaviors.memoryChanged(c.player(),c.npc(),c.instance(),c.key(),c.oldValue()==null?null:c.oldValue().value(),c.newValue()==null?null:c.newValue().value(),c.source());getServer().getPluginManager().callEvent(new NpcMemoryChangeEvent(c.player(),c.npc(),c.instance(),c.key(),c.oldValue(),c.newValue(),c.source()));}));
        CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(PersonaTrait.class).withName("persona"));
        PersonaCommand command=new PersonaCommand(this);getCommand("persona").setExecutor(command);getCommand("persona").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new PersonaListener(this),this);
        getServer().getPluginManager().registerEvents(new Listener(){@EventHandler public void disabled(PluginDisableEvent event){if(event.getPlugin()!=Main.this)api.unregister(event.getPlugin());}},this);
        getServer().getScheduler().runTask(this,()->{try{registry.loadAndReplace(this::loadContent);api.initialLoadComplete();getServer().getOnlinePlayers().forEach(states::load);}catch(ContentException e){e.errors().forEach(x->getLogger().severe("Content: "+x));getServer().getPluginManager().disablePlugin(this);}});
        getServer().getScheduler().runTaskTimer(this,()->getServer().getOnlinePlayers().forEach(p->quests.tickPlayer(p,false)),20,20);
        getServer().getScheduler().runTaskTimer(this,behaviors::tick,1,1);
        getServer().getScheduler().runTaskTimerAsynchronously(this,memories::flush,100,100);
        getServer().getScheduler().runTaskTimer(this,()->behaviors.flush(false),100,100);
        long memorySweepTicks=Math.max(20,Durations.parse(getConfig().getString("memory.expiry.sweep-interval","60s")).toMillis()/50);
        getServer().getScheduler().runTaskTimerAsynchronously(this,memories::sweep,memorySweepTicks,memorySweepTicks);
    }

    @Override
    public void onDisable() {
        if(dialogues!=null)dialogues.close();
        if(behaviors!=null)behaviors.close();
        if(extensionLoader!=null)extensionLoader.close();
        if(api!=null)api.shutdown();
        if(states!=null)states.saveAll();
        if(memories!=null)memories.close();
        if(store!=null)store.close();
    }

    private Content.Registry loadContent()throws ContentException{ContentValidator.Report report=validateContent();if(!report.valid())throw new ContentException(report.errors());return report.candidate();}
    /** Validates a complete candidate without changing active content or runtime state. */
    public ContentValidator.Report validateContent(){try{YamlConfiguration disk=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"config.yml"));Duration delay=Durations.parse(disk.getString("dialogue.default-line-delay","2s"));return new ContentValidator(getDataFolder(),delay,api).validate();}catch(RuntimeException e){return new ContentValidator.Report(false,java.util.List.of("config.yml:1:1: "+java.util.Objects.toString(e.getMessage(),e.getClass().getSimpleName())),null);}}
    public boolean reloadPersona(){reloadConfig();try{Content.Registry candidate=loadContent();if(memories!=null)configureMemories();if(dialogues!=null)dialogues.cancelAll("Conversation cancelled by content reload.");registry.loadAndReplace(()->candidate);if(behaviors!=null)behaviors.reload();if(memories!=null)memories.flush();return true;}catch(ContentException e){e.errors().forEach(x->getLogger().severe("Persona reload rejected: "+x));return false;}catch(RuntimeException e){getLogger().severe("Persona reload rejected: "+e.getMessage());return false;}}
    private void saveExample(String path){if(!new File(getDataFolder(),path).exists())saveResource(path,false);}
    private void configureMemories(){
        memories.expiredRetention(Durations.parse(getConfig().getString("memory.expiry.retention","0s")));
        api.expansions().forEach(id->memories.claimNamespace(id,id));
        var section=getConfig().getConfigurationSection("memory.namespaces");if(section!=null)for(String namespace:section.getKeys(false))memories.claimNamespace(namespace,section.getString(namespace,namespace));
    }
    public Content.Registry registry(){return registry.get();}
    public StateManager states(){return states;}
    public QuestService quests(){return quests;}
    public DialogueService dialogues(){return dialogues;}
    public EffectExecutor effects(){return effects;}
    public ScriptEngine scripts(){return scripts;}
    public PersonaApi api(){return api;}
    public PersistentNpcMemoryService memories(){return memories;}
    public BehaviorService behaviors(){return behaviors;}
    public SqliteStore store(){return store;}
}
