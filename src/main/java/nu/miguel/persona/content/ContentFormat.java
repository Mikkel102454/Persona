package nu.miguel.persona.content;

import org.bukkit.configuration.ConfigurationSection;

/** Version of Persona's YAML compatibility contract, independent of plugin/API versions. */
public final class ContentFormat {
    public static final int CURRENT=2;
    private ContentFormat() {}
    public static void validate(ConfigurationSection yaml,Validation.Source source){
        Object raw=yaml.get("content-version");
        if(!(raw instanceof Number n)||n.intValue()!=CURRENT||n.doubleValue()!=n.intValue())
            throw new IllegalArgumentException("this resource requires content-version: 2; legacy list hooks and unversioned content must be migrated manually");
    }
    public static void validateBehavior(ConfigurationSection yaml){
        if(!yaml.contains("content-version"))return;
        Object raw=yaml.get("content-version");
        if(!(raw instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<1||n.intValue()>CURRENT)
            throw new IllegalArgumentException("unsupported behavior content-version "+raw+"; supported versions are 1 and 2");
    }
}
