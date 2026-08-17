package nu.miguel.persona.content;

import org.bukkit.configuration.ConfigurationSection;

/** Version of Persona's YAML compatibility contract, independent of plugin/API versions. */
public final class ContentFormat {
    public static final int CURRENT=1;
    private ContentFormat() {}
    public static void validate(ConfigurationSection yaml,Validation.Source source){
        if(!yaml.contains("content-version"))return; // unversioned files are format 1 for compatibility
        Object raw=yaml.get("content-version");if(!(raw instanceof Number n)||n.intValue()!=CURRENT||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("unsupported content-version "+raw+"; supported version is "+CURRENT);
    }
}
