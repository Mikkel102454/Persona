package nu.miguel.persona.content;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Shared diagnostics and strict-key helpers used by every content parser. */
public final class Validation {
    private Validation() {}

    public record Source(String name, List<String> lines) {
        public static Source read(File root, File file) {
            String name=root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');
            try { return new Source(name, Files.readAllLines(file.toPath())); }
            catch (IOException ignored) { return new Source(name,List.of()); }
        }
        public String at(String hint,String message) {
            String needle=hint==null?null:hint+":";
            for(int i=0;i<lines.size();i++){
                String line=lines.get(i);int column=needle==null?-1:line.indexOf(needle);
                if(column>=0)return name+":"+(i+1)+":"+(column+1)+": "+message;
            }
            return name+":1:1: "+message;
        }
    }

    public static void keys(ConfigurationSection section,Set<String> allowed) {
        for(String key:section.getKeys(false))if(!allowed.contains(key))throw unknown(key,allowed);
    }
    public static void keys(Map<?,?> map,Set<String> allowed) {
        for(Object raw:map.keySet()){String key=String.valueOf(raw);if(!allowed.contains(key))throw unknown(key,allowed);}
    }
    public static IllegalArgumentException unknown(String key,Collection<String> allowed) {
        String suggestion=allowed.stream().min(Comparator.comparingInt(x->distance(key,x))).filter(x->distance(key,x)<=Math.max(2,key.length()/3)).orElse(null);
        return new IllegalArgumentException("unknown key '"+key+"'"+(suggestion==null?"":"; did you mean '"+suggestion+"'?"));
    }
    /** Validates the useful JSON-Schema subset exposed by behavior extensions. */
    public static void schema(Map<String,Object> schema,Map<String,Object> value,String path) {
        if(schema==null||schema.isEmpty())return;
        Object required=schema.get("required");if(required instanceof Collection<?> keys)for(Object key:keys)if(!value.containsKey(String.valueOf(key)))throw new IllegalArgumentException(path+" needs "+key);
        Object properties=schema.get("properties");Map<?,?> definitions=properties instanceof Map<?,?> map?map:Map.of();
        if(Boolean.FALSE.equals(schema.get("additionalProperties")))for(String key:value.keySet())if(!definitions.containsKey(key))throw unknown(key,definitions.keySet().stream().map(String::valueOf).toList());
        for(var entry:value.entrySet()){Object rawRule=definitions.get(entry.getKey());if(!(rawRule instanceof Map<?,?> rule))continue;checkRule(entry.getValue(),rule,path+"."+entry.getKey());}
    }
    private static void checkRule(Object value,Map<?,?> rule,String path){
        Object type=rule.get("type");boolean valid=switch(String.valueOf(type)){case "string"->value instanceof String;case "number"->value instanceof Number;case "integer"->value instanceof Number n&&n.doubleValue()==n.longValue();case "boolean"->value instanceof Boolean;case "array"->value instanceof Collection<?>;case "object"->value instanceof Map<?,?>;default->true;};if(!valid)throw new IllegalArgumentException(path+" must be a "+type);
        if(rule.get("enum") instanceof Collection<?> values&&!values.contains(value))throw new IllegalArgumentException(path+" must be one of "+values);
        if(value instanceof Number number){double n=number.doubleValue();if(rule.get("minimum") instanceof Number min&&n<min.doubleValue())throw new IllegalArgumentException(path+" must be at least "+min);if(rule.get("exclusiveMinimum") instanceof Number min&&n<=min.doubleValue())throw new IllegalArgumentException(path+" must be greater than "+min);if(rule.get("maximum") instanceof Number max&&n>max.doubleValue())throw new IllegalArgumentException(path+" must be at most "+max);if(rule.get("exclusiveMaximum") instanceof Number max&&n>=max.doubleValue())throw new IllegalArgumentException(path+" must be less than "+max);}
        if(value instanceof String text&&rule.get("minLength") instanceof Number min&&text.length()<min.intValue())throw new IllegalArgumentException(path+" is too short");
    }
    private static int distance(String a,String b){
        int[] previous=new int[b.length()+1];for(int j=0;j<=b.length();j++)previous[j]=j;
        for(int i=1;i<=a.length();i++){int[] current=new int[b.length()+1];current[0]=i;for(int j=1;j<=b.length();j++)current[j]=Math.min(Math.min(current[j-1]+1,previous[j]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));previous=current;}return previous[b.length()];
    }
}
