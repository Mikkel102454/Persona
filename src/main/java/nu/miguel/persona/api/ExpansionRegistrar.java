package nu.miguel.persona.api;

public interface ExpansionRegistrar {
    void condition(String name, ExpansionTypes.Condition handler);
    void command(String name, ExpansionTypes.Command handler);
    void placeholder(String name, ExpansionTypes.Placeholder handler);
    void objective(String name, ExpansionTypes.Objective handler);
}
