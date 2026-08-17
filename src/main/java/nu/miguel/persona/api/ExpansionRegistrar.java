package nu.miguel.persona.api;

public interface ExpansionRegistrar {
    void condition(String name, ExpansionTypes.Condition handler);
    void command(String name, ExpansionTypes.Command handler);
    void placeholder(String name, ExpansionTypes.Placeholder handler);
    void objective(String name, ExpansionTypes.Objective handler);
    void behaviorCondition(String name, ExpansionTypes.BehaviorCondition handler);
    void behaviorAction(String name, ExpansionTypes.BehaviorAction handler);
    /** Registers metadata for an extension-defined current or future content type. */
    void editorSchema(String contentType, String name, EditorSchemaProvider provider);
    /** Registers a read-only, bounded live catalog. The catalog ID is extension-namespaced. */
    void editorCatalog(String name, EditorCatalogProvider provider);
}
