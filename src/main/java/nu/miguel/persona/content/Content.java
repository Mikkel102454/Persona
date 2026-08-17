package nu.miguel.persona.content;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import nu.miguel.persona.behavior.BehaviorDefinition;

/** Immutable, fully validated representation of Persona 2.0 YAML content. */
public final class Content {
    private Content() {}

    public record Registry(Map<String,Npc> npcs, Map<String,Dialogue> dialogues,
                           Map<String,Quest> quests, Map<String,List<Step>> scripts,
                           Map<String,BehaviorDefinition> behaviors) {
        public Registry(Map<String,Npc> npcs,Map<String,Dialogue> dialogues,Map<String,Quest> quests){this(npcs,dialogues,quests,Map.of(),Map.of());}
        public Registry(Map<String,Npc> npcs,Map<String,Dialogue> dialogues,Map<String,Quest> quests,Map<String,List<Step>> scripts){this(npcs,dialogues,quests,scripts,Map.of());}
        public static Registry empty(){return new Registry(Map.of(),Map.of(),Map.of(),Map.of(),Map.of());}
    }

    public record Npc(String id,String displayName,List<DialogueRegistration> dialogues,
                      List<Step> onInteract,List<Step> onNoDialogue,
                      Map<String,Anchor> anchors,String sharedBehavior,String playerBehavior) {
        public Npc(String id,String displayName,List<DialogueRegistration> dialogues){this(id,displayName,dialogues,List.of(),List.of(),Map.of(),null,null);}
        public Npc(String id,String displayName,List<DialogueRegistration> dialogues,List<Step> onInteract,List<Step> onNoDialogue){this(id,displayName,dialogues,onInteract,onNoDialogue,Map.of(),null,null);}
    }
    public record Anchor(String world,double x,double y,double z,float yaw,float pitch) {}
    public record DialogueRegistration(String dialogueId,int priority,Condition condition) {}
    public record Dialogue(String id,String start,Map<String,Node> nodes) {}
    public record Node(String id,List<Step> script) {}

    public sealed interface Step permits Say,If,ChoiceStep,Goto,EndDialogue,Stop,Wait,RandomStep,RunScript,Command {}
    public record WeightedText(String text,int weight) {}
    public record Say(String text,String textKey,Map<String,String> translations,List<WeightedText> variants,Duration delay) implements Step {
        public Say { translations=Map.copyOf(translations);variants=List.copyOf(variants); }
        public Say(String text,List<WeightedText> variants,Duration delay){this(text,null,Map.of(),variants,delay);}
    }
    public record If(Condition when,List<Step> thenScript,List<Step> elseScript) implements Step {}
    public record ChoiceStep(List<ChoiceOption> options) implements Step {}
    public record ChoiceOption(String text,Condition when,List<Step> script) {}
    public record Goto(String node,String dialogue) implements Step {}
    public record EndDialogue() implements Step {}
    public record Stop() implements Step {}
    public record Wait(Duration duration) implements Step {}
    public record RandomStep(List<WeightedScript> options) implements Step {}
    public record WeightedScript(int weight,List<Step> script) {}
    public record RunScript(String script) implements Step {}
    public record Command(String type,Map<String,Object> options,List<Step> onSuccess,List<Step> onFailure) implements Step {
        public Command { options=Map.copyOf(options);onSuccess=List.copyOf(onSuccess);onFailure=List.copyOf(onFailure); }
    }

    public sealed interface Condition permits All,Any,Not,QuestStateCondition,ItemCount,Flag,
            VariableCondition,PermissionCondition,WorldCondition,ChanceCondition,CustomCondition {}
    public record All(List<Condition> conditions) implements Condition {}
    public record Any(List<Condition> conditions) implements Condition {}
    public record Not(Condition condition) implements Condition {}
    public record QuestStateCondition(String quest,QuestState state) implements Condition {}
    public record ItemCount(Material material,int amount) implements Condition {}
    public record Flag(String name,boolean value) implements Condition {}
    public record VariableCondition(String name,Comparison operator,String value) implements Condition {}
    public record PermissionCondition(String permission) implements Condition {}
    public record WorldCondition(String world) implements Condition {}
    public record ChanceCondition(double chance) implements Condition {}
    public record CustomCondition(String type,Map<String,Object> options) implements Condition {}
    public enum Comparison { EQUALS,NOT_EQUALS,GREATER_THAN,GREATER_THAN_OR_EQUAL,LESS_THAN,LESS_THAN_OR_EQUAL,CONTAINS }
    public enum QuestState { NOT_STARTED,ACTIVE,COMPLETED }
    public enum VariableOperation { SET,ADD,SUBTRACT,MULTIPLY,DELETE }

    public record Quest(String id,String title,String description,List<Phase> phases,
                        Condition requirements,boolean repeatable,Duration cooldown,int maximumCompletions,
                        Duration timeLimit,List<Step> onStart,List<Step> onComplete,List<Step> onFail,List<Step> onReset) {}
    public record Phase(String id,String title,String description,List<Objective> objectives,
                        List<Step> onStart,List<Step> onComplete,List<PhaseBranch> branches) {}
    public record PhaseBranch(Condition condition,String nextPhase) {}
    public record ProgressHook(long every,List<Step> script) {}
    public record Objective(String id,String title,String description,ObjectiveType type,
                            Material material,EntityType entity,int amount,String npc,String instance,
                            Position position,double radius,Duration duration,boolean optional,boolean hidden,
                            List<Step> onStart,ProgressHook onProgress,List<Step> onComplete,
                            String extensionType,Map<String,Object> options,long requiredProgress) {
        public Objective { options=Map.copyOf(options); }
    }
    public record Position(String world,double x,double y,double z) {
        public boolean contains(Location location,double radius){return location.getWorld()!=null&&location.getWorld().getName().equals(world)&&location.distanceSquared(new Location(location.getWorld(),x,y,z))<=radius*radius;}
    }
    public enum ObjectiveType { COLLECT_ITEM,DELIVER_ITEM,TALK_TO_NPC,KILL_ENTITY,GO_TO_LOCATION,INTERACT_BLOCK,WAIT,SURVIVE,CUSTOM }

    /* Internal adapter retained so the low-level Bukkit mutation implementation stays isolated. */
    public record Effect(EffectType type,Map<String,Object> options,List<Effect> nested) {}
    public enum EffectType { MESSAGE,ACTION_BAR,TITLE,PLAY_SOUND,PARTICLE,GIVE_ITEM,TAKE_ITEM,GIVE_EXPERIENCE,SET_FLAG,SET_VARIABLE,RUN_COMMAND,TELEPORT,LIGHTNING_EFFECT,POTION_EFFECT,BROADCAST,SPAWN_ENTITY,SET_BLOCK,NPC_ANIMATION,NPC_SPEAK,NPC_MOVE,WAIT,SEQUENCE,RANDOM,CUSTOM }
}
