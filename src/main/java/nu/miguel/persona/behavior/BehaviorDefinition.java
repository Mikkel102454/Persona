package nu.miguel.persona.behavior;

import java.util.List;
import java.util.Map;

/** Immutable behavior tree produced only after complete validation. */
public record BehaviorDefinition(String id, BehaviorScope scope, BehaviorNode root,
                                 Map<String,BehaviorNode> nodes, String hash) {
    public BehaviorDefinition {
        nodes=Map.copyOf(nodes);
    }

    public record BehaviorNode(String id,String type,List<BehaviorNode> children,
                               BehaviorNode child,String subtree,Map<String,Object> options) {
        public BehaviorNode {
            children=List.copyOf(children);
            options=Map.copyOf(options);
        }
    }
}
