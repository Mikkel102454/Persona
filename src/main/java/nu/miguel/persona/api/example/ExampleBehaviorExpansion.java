package nu.miguel.persona.api.example;

import nu.miguel.persona.api.*;
import nu.miguel.persona.behavior.BehaviorScope;
import nu.miguel.persona.behavior.BehaviorStatus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Copyable API example: a player condition and cancellable asynchronous action. */
public final class ExampleBehaviorExpansion extends PersonaExpansion {
    public String identifier(){return "example";}public String author(){return "Persona";}public String version(){return "1.0";}
    protected void registerTypes(ExpansionRegistrar registrar){
        registrar.behaviorCondition("has-player",new ExpansionTypes.BehaviorCondition(){
            public boolean test(BehaviorContext context,Map<String,Object> data){return context.player().isPresent();}
            public BehaviorNodeMetadata metadata(){return new BehaviorNodeMetadata(Set.of(BehaviorScope.PLAYER),Set.of("player-join"),Map.of(),Map.of("type","object","additionalProperties",false));}
        });
        registrar.behaviorAction("async-success",new ExpansionTypes.BehaviorAction(){
            public CompletionStage<BehaviorStatus> execute(BehaviorContext context,Map<String,Object> data){return execute(context,data,new CancellationToken());}
            public CompletionStage<BehaviorStatus> execute(BehaviorContext context,Map<String,Object> data,CancellationToken token){
                CompletableFuture<BehaviorStatus> result=new CompletableFuture<>();token.onCancel(()->result.complete(BehaviorStatus.FAILURE));
                CompletableFuture.runAsync(()->services().completeSync(()->{if(!token.isCancelled())result.complete(BehaviorStatus.SUCCESS);}));return result;
            }
            public BehaviorNodeMetadata metadata(){return new BehaviorNodeMetadata(Set.of(BehaviorScope.SHARED,BehaviorScope.PLAYER),Set.of(),Map.of("attempts",Long.class),Map.of("type","object","additionalProperties",false));}
        });
    }
}
