package nu.miguel.persona.api;

import nu.miguel.persona.behavior.BehaviorScope;

import java.util.Map;
import java.util.Set;

/** Additive API 2.x declaration used by validation, editors, and diagnostics. */
public record BehaviorNodeMetadata(Set<BehaviorScope> scopes,Set<String> wakeEvents,
                                   Map<String,Class<?>> durableFields,Map<String,Object> schema) {
    public BehaviorNodeMetadata {
        scopes=scopes==null||scopes.isEmpty()?Set.of(BehaviorScope.SHARED,BehaviorScope.PLAYER):Set.copyOf(scopes);
        wakeEvents=wakeEvents==null?Set.of():Set.copyOf(wakeEvents);
        durableFields=durableFields==null?Map.of():Map.copyOf(durableFields);
        schema=schema==null?Map.of():Map.copyOf(schema);
    }
    public static BehaviorNodeMetadata anyScope(){return new BehaviorNodeMetadata(null,null,null,null);}
}
