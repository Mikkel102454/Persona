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
import nu.miguel.persona.editor.EditorClient;
import nu.miguel.persona.editor.protocol.ValidationProject;
import nu.miguel.persona.editor.protocol.ValidationResult;
import nu.miguel.persona.editor.protocol.PublishProject;
import nu.miguel.persona.editor.protocol.PublishApplyResult;
import nu.miguel.persona.editor.protocol.RollbackProject;
import nu.miguel.persona.editor.protocol.RollbackApplyResult;

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
    private EditorClient editor;
    private String editorError="disabled by configuration";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveExample("examples/npcs/builder.yml.example");saveExample("examples/dialogues/builder.yml.example");saveExample("examples/dialogues/builder_delivery.yml.example");saveExample("examples/dialogues/builder_thanks.yml.example");saveExample("examples/quests/supplies.yml.example");
        saveExample("examples/npcs/trial_master.yml.example");saveExample("examples/dialogues/trial_intro.yml.example");saveExample("examples/dialogues/trial_active.yml.example");saveExample("examples/dialogues/trial_complete.yml.example");saveExample("examples/quests/adventurers_trial.yml.example");
        saveExample("examples/scripts/quest-success.yml.example");saveExample("examples/scripts/dramatic-warning.yml.example");
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
        configureEditor();
        CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(PersonaTrait.class).withName("persona"));
        PersonaCommand command=new PersonaCommand(this);getCommand("persona").setExecutor(command);getCommand("persona").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new PersonaListener(this),this);
        getServer().getPluginManager().registerEvents(new Listener(){@EventHandler public void disabled(PluginDisableEvent event){if(event.getPlugin()!=Main.this)api.unregister(event.getPlugin());}},this);
        getServer().getScheduler().runTask(this,()->{try{registry.loadAndReplace(this::loadContent);api.initialLoadComplete();getServer().getOnlinePlayers().forEach(states::load);}catch(ContentException e){e.errors().forEach(x->getLogger().severe("Content: "+x));getServer().getPluginManager().disablePlugin(this);}});
        getServer().getScheduler().runTaskTimer(this,()->getServer().getOnlinePlayers().forEach(p->quests.tickPlayer(p,false)),20,20);
        getServer().getScheduler().runTaskTimer(this,()->{if(editor!=null)editor.revokeLostPermissions();},20,20);
        getServer().getScheduler().runTaskTimer(this,behaviors::tick,1,1);
        getServer().getScheduler().runTaskTimerAsynchronously(this,memories::flush,100,100);
        getServer().getScheduler().runTaskTimer(this,()->behaviors.flush(false),100,100);
        long memorySweepTicks=Math.max(20,Durations.parse(getConfig().getString("memory.expiry.sweep-interval","60s")).toMillis()/50);
        getServer().getScheduler().runTaskTimerAsynchronously(this,memories::sweep,memorySweepTicks,memorySweepTicks);
    }

    @Override
    public void onDisable() {
        if(editor!=null)editor.close();
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
    /** Validates an isolated hosted-editor candidate through the same parser without activating it. */
    public ValidationResult validateEditorProject(ValidationProject project){YamlConfiguration disk=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"config.yml"));Duration delay=Durations.parse(disk.getString("dialogue.default-line-delay","2s"));return nu.miguel.persona.editor.EditorProjectValidator.validate(getDataFolder().toPath(),project,delay,api);}
    /** Revalidates, backs up, writes, and activates an editor candidate as one recoverable transaction. */
    public PublishApplyResult publishEditorProject(PublishProject project){
        if(!getConfig().getBoolean("editor.publish-enabled",false))return new PublishApplyResult(nu.miguel.persona.editor.protocol.Protocol.VERSION,project.publishId(),false,project.baseRevision(),null,"Hosted publication is disabled in config.yml");
        YamlConfiguration disk=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"config.yml"));Duration delay=Durations.parse(disk.getString("dialogue.default-line-delay","2s"));
        PublishApplyResult result=nu.miguel.persona.editor.EditorContentPublisher.publish(getDataFolder().toPath(),project,delay,api,this::activateEditorCandidate);
        if(result.success()&&editor!=null)editor.contentChanged();return result;
    }
    public RollbackApplyResult rollbackEditorProject(RollbackProject project){
        if(!getConfig().getBoolean("editor.publish-enabled",false))return new RollbackApplyResult(nu.miguel.persona.editor.protocol.Protocol.VERSION,project.rollbackId(),project.publishId(),false,project.currentRevision(),null,"Hosted publication is disabled in config.yml");
        YamlConfiguration disk=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"config.yml"));Duration delay=Durations.parse(disk.getString("dialogue.default-line-delay","2s"));
        RollbackApplyResult result=nu.miguel.persona.editor.EditorContentPublisher.rollback(getDataFolder().toPath(),project,delay,api,this::activateEditorCandidate);
        if(result.success()&&editor!=null)editor.contentChanged();return result;
    }
    private void activateEditorCandidate(Content.Registry candidate)throws Exception{Content.Registry previous=registry.get();try{registry.loadAndReplace(()->candidate);if(dialogues!=null)dialogues.cancelAll("Conversation cancelled by editor content change.");if(scripts!=null)scripts.clearState();if(behaviors!=null)behaviors.reload();if(memories!=null)memories.flush();}catch(Exception failure){registry.loadAndReplace(()->previous);if(behaviors!=null)behaviors.reload();throw failure;}}
    public boolean reloadPersona(){reloadConfig();try{Content.Registry candidate=loadContent();if(memories!=null)configureMemories();if(dialogues!=null)dialogues.cancelAll("Conversation cancelled by content reload.");registry.loadAndReplace(()->candidate);if(scripts!=null)scripts.clearState();if(behaviors!=null)behaviors.reload();if(memories!=null)memories.flush();configureEditor();if(editor!=null)editor.contentChanged();return true;}catch(ContentException e){e.errors().forEach(x->getLogger().severe("Persona reload rejected: "+x));return false;}catch(RuntimeException e){getLogger().severe("Persona reload rejected: "+e.getMessage());return false;}}
    private void configureEditor(){
        boolean enabled=getConfig().getBoolean("editor.enabled",false);
        String hostedUrl=getConfig().getString("editor.hosted-url","https://editor.persona.invalid");
        boolean insecure=getConfig().getBoolean("editor.allow-insecure-transport",false);
        if(enabled&&editor!=null&&editor.matchesConfiguration(hostedUrl,insecure))return;
        if(editor!=null){editor.close();editor=null;}
        if(!enabled){editorError="disabled by configuration";return;}
        try{editor=new EditorClient(this);editorError=null;getLogger().info("Persona hosted editor client enabled for "+getConfig().getString("editor.hosted-url"));}
        catch(Exception e){editorError=java.util.Objects.toString(e.getMessage(),e.getClass().getSimpleName());getLogger().severe("Persona editor disabled: "+editorError);}
    }
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
    public PersistentNpcMemoryService memory(){return memories;}
    public BehaviorService behaviors(){return behaviors;}
    public SqliteStore store(){return store;}
    public EditorClient editor(){return editor;}
    public String editorError(){return editorError;}
}
