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
    /** Validates the data-only JSON-Schema subset supported by Persona's extension editor contract. */
    public static void schema(Map<String,Object> schema,Map<String,Object> value,String path) {
        if(schema==null||schema.isEmpty())return;checkRule(value,schema,path);
    }
    private static void checkRule(Object value,Map<?,?> rule,String path){
        try{checkRuleInner(value,rule,path);}catch(IllegalArgumentException error){Object message=rule.get("x-persona-validation-message");if(message instanceof String text&&!text.isBlank())throw new IllegalArgumentException(path+": "+text);throw error;}
    }
    private static void checkRuleInner(Object value,Map<?,?> rule,String path){
        Object type=rule.get("type");boolean valid=!rule.containsKey("type")||switch(String.valueOf(type)){case "string"->value instanceof String;case "number"->value instanceof Number;case "integer"->value instanceof Number n&&n.doubleValue()==n.longValue();case "boolean"->value instanceof Boolean;case "array"->value instanceof Collection<?>;case "object"->value instanceof Map<?,?>;case "null"->value==null;default->true;};if(!valid)throw new IllegalArgumentException(path+" must be a "+type);
        if(rule.containsKey("const")&&!Objects.equals(rule.get("const"),value))throw new IllegalArgumentException(path+" must equal "+rule.get("const"));
        if(rule.get("enum") instanceof Collection<?> values&&!values.contains(value))throw new IllegalArgumentException(path+" must be one of "+values);
        validateCombinations(value,rule,path);
        if(value instanceof Number number){double n=number.doubleValue();if(rule.get("minimum") instanceof Number min&&n<min.doubleValue())throw new IllegalArgumentException(path+" must be at least "+min);if(rule.get("exclusiveMinimum") instanceof Number min&&n<=min.doubleValue())throw new IllegalArgumentException(path+" must be greater than "+min);if(rule.get("maximum") instanceof Number max&&n>max.doubleValue())throw new IllegalArgumentException(path+" must be at most "+max);if(rule.get("exclusiveMaximum") instanceof Number max&&n>=max.doubleValue())throw new IllegalArgumentException(path+" must be less than "+max);if(rule.get("multipleOf") instanceof Number step&&step.doubleValue()!=0&&Math.abs(n/step.doubleValue()-Math.rint(n/step.doubleValue()))>1e-9)throw new IllegalArgumentException(path+" must be a multiple of "+step);}
        if(value instanceof String text){if(rule.get("minLength") instanceof Number min&&text.length()<min.intValue())throw new IllegalArgumentException(path+" is too short");if(rule.get("maxLength") instanceof Number max&&text.length()>max.intValue())throw new IllegalArgumentException(path+" is too long");if(rule.get("pattern") instanceof String pattern&&!java.util.regex.Pattern.compile(pattern).matcher(text).find())throw new IllegalArgumentException(path+" does not match "+pattern);if("duration".equals(rule.get("format")))try{Durations.parse(text);}catch(RuntimeException error){throw new IllegalArgumentException(path+" must be a duration");}}
        if(value instanceof Collection<?> values){if(rule.get("minItems") instanceof Number min&&values.size()<min.intValue())throw new IllegalArgumentException(path+" needs at least "+min+" items");if(rule.get("maxItems") instanceof Number max&&values.size()>max.intValue())throw new IllegalArgumentException(path+" allows at most "+max+" items");if(Boolean.TRUE.equals(rule.get("uniqueItems"))&&new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException(path+" items must be unique");if(rule.get("items") instanceof Map<?,?> itemRule){int index=0;for(Object item:values)checkRule(item,itemRule,path+"["+(index++)+"]");}}
        if(value instanceof Map<?,?> map)checkObject(map,rule,path);
    }
    private static void checkObject(Map<?,?> value,Map<?,?> rule,String path){
        if(rule.get("minProperties") instanceof Number min&&value.size()<min.intValue())throw new IllegalArgumentException(path+" needs at least "+min+" fields");
        if(rule.get("maxProperties") instanceof Number max&&value.size()>max.intValue())throw new IllegalArgumentException(path+" allows at most "+max+" fields");
        Object required=rule.get("required");if(required instanceof Collection<?> keys)for(Object key:keys)if(!value.containsKey(String.valueOf(key)))throw new IllegalArgumentException(path+" needs "+key);
        Map<?,?> definitions=rule.get("properties") instanceof Map<?,?> map?map:Map.of();
        for(var entry:value.entrySet()){Object raw=definitions.get(entry.getKey());if(raw instanceof Map<?,?> child)checkRule(entry.getValue(),child,path+"."+entry.getKey());else if(Boolean.FALSE.equals(rule.get("additionalProperties")))throw unknown(String.valueOf(entry.getKey()),definitions.keySet().stream().map(String::valueOf).toList());else if(rule.get("additionalProperties") instanceof Map<?,?> child)checkRule(entry.getValue(),child,path+"."+entry.getKey());}
        if(rule.get("dependentRequired") instanceof Map<?,?> dependencies)for(var entry:dependencies.entrySet())if(value.containsKey(entry.getKey())&&entry.getValue() instanceof Collection<?> fields)for(Object field:fields)if(!value.containsKey(String.valueOf(field)))throw new IllegalArgumentException(path+" needs "+field+" when "+entry.getKey()+" is present");
    }
    private static void validateCombinations(Object value,Map<?,?> rule,String path){
        if(rule.get("allOf") instanceof Collection<?> all)for(Object candidate:all)if(candidate instanceof Map<?,?> child)checkRule(value,child,path);
        if(rule.get("anyOf") instanceof Collection<?> any&&!any.isEmpty()&&matches(value,any,path)==0)throw new IllegalArgumentException(path+" does not match any allowed schema");
        if(rule.get("oneOf") instanceof Collection<?> one&&!one.isEmpty()&&matches(value,one,path)!=1)throw new IllegalArgumentException(path+" must match exactly one allowed schema");
        if(rule.get("not") instanceof Map<?,?> forbidden&&matches(value,List.of(forbidden),path)>0)throw new IllegalArgumentException(path+" contains a forbidden field combination");
    }
    private static int matches(Object value,Collection<?> candidates,String path){int matches=0;for(Object candidate:candidates)if(candidate instanceof Map<?,?> child)try{checkRule(value,child,path);matches++;}catch(IllegalArgumentException ignored){}return matches;
    }
    private static int distance(String a,String b){
        int[] previous=new int[b.length()+1];for(int j=0;j<=b.length();j++)previous[j]=j;
        for(int i=1;i<=a.length();i++){int[] current=new int[b.length()+1];current[0]=i;for(int j=1;j<=b.length();j++)current[j]=Math.min(Math.min(current[j-1]+1,previous[j]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));previous=current;}return previous[b.length()];
    }
}
