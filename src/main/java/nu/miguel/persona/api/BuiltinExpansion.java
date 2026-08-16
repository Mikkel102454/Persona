package nu.miguel.persona.api;

import java.util.Map;

/** Registers Persona's 2.0 command vocabulary for discovery and collision checking. */
public final class BuiltinExpansion extends PersonaExpansion {
    private static final String[] CONDITIONS={"quest-state","item-count","flag","variable","permission","world","chance"};
    private static final String[] COMMANDS={"start-quest","finish-quest","deliver-items","set-flag","set-variable","message","action-bar","title","play-sound","particle","give-item","take-item","give-experience","run-command","teleport","lightning-effect","potion-effect","broadcast","spawn-entity","set-block","npc-animation","npc-speak","npc-move"};
    private static final String[] OBJECTIVES={"collect-item","deliver-item","talk-to-npc","kill-entity","go-to-location","interact-block","wait","survive"};
    public String identifier(){return "persona";}public String author(){return "Persona";}public String version(){return PersonaApi.API_VERSION;}
    protected void registerTypes(ExpansionRegistrar r){
        for(String n:CONDITIONS)r.condition(n,(c,d)->false);
        for(String n:COMMANDS)r.command(n,(c,d)->java.util.concurrent.CompletableFuture.completedFuture(ExpansionTypes.CommandResult.success()));
        for(String n:OBJECTIVES)r.objective(n,d->new ExpansionTypes.ObjectiveDefinition(1,d));
        r.placeholder("player",(c,a)->c.player().getName());r.placeholder("world",(c,a)->c.player().getWorld().getName());r.placeholder("flag",(c,a)->String.valueOf(c.api().flag(c.player(),a).orElse(false)));r.placeholder("variable",(c,a)->c.api().variable(c.player(),a).orElse(""));
    }
}
