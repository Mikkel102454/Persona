package nu.miguel.persona.quest;

import nu.miguel.persona.content.Content.*;
import nu.miguel.persona.state.PlayerState;
import org.bukkit.entity.Player;
import nu.miguel.persona.Main;
import nu.miguel.persona.api.ExpansionTypes;
import nu.miguel.persona.script.EffectExecutor;

public final class ConditionEvaluator {
    private final Main plugin;
    public ConditionEvaluator(){this.plugin=null;}
    public ConditionEvaluator(Main plugin){this.plugin=plugin;}
    public boolean test(Condition c, Player player, PlayerState state) {
        if(c instanceof All x)return x.conditions().stream().allMatch(v->test(v,player,state));
        if(c instanceof Any x)return x.conditions().stream().anyMatch(v->test(v,player,state));
        if(c instanceof Not x)return !test(x.condition(),player,state);
        if(c instanceof QuestStateCondition x)return state.questState(x.quest())==x.state();
        if(c instanceof ItemCount x)return player.getInventory().all(x.material()).values().stream().mapToInt(i->i.getAmount()).sum()>=x.amount();
        if(c instanceof Flag x)return state.flags().getOrDefault(x.name(),false)==x.value();
        if(c instanceof VariableCondition x)return compare(state.variables().getOrDefault(x.name(),""),x.operator(),x.value());
        if(c instanceof PermissionCondition x)return player.hasPermission(x.permission());
        if(c instanceof WorldCondition x)return player.getWorld().getName().equals(x.world());
        if(c instanceof ChanceCondition x)return Math.random()<x.chance();
        if(c instanceof CustomCondition x&&plugin!=null){try{return plugin.api().handler(ExpansionTypes.Condition.class,x.type()).orElseThrow().test(plugin.api().context(EffectExecutor.Context.player(player),x.type()),x.options());}catch(RuntimeException e){plugin.getLogger().warning("Condition "+x.type()+" failed: "+e.getMessage());return false;}}
        return false;
    }

    private boolean compare(String actual,Comparison operator,String expected){
        if(operator==Comparison.EQUALS)return actual.equals(expected);
        if(operator==Comparison.NOT_EQUALS)return !actual.equals(expected);
        if(operator==Comparison.CONTAINS)return actual.contains(expected);
        try{double a=Double.parseDouble(actual),b=Double.parseDouble(expected);return switch(operator){case GREATER_THAN->a>b;case GREATER_THAN_OR_EQUAL->a>=b;case LESS_THAN->a<b;case LESS_THAN_OR_EQUAL->a<=b;default->false;};}
        catch(NumberFormatException e){return false;}
    }
}
