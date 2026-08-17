package nu.miguel.persona.api;

import java.util.*;

/** Persona's optional vocabulary layered on top of JSON Schema. */
public final class EditorSchemaAnnotations {
    public static final String WIDGET="x-persona-widget";
    public static final String CATALOG="x-persona-catalog";
    public static final String REFERENCE_TYPE="x-persona-reference-type";
    public static final String ORDER="x-persona-order";
    public static final String DEPENDS_ON="x-persona-depends-on";
    public static final String VALIDATION_MESSAGE="x-persona-validation-message";
    public static final Set<String> WIDGETS=Set.of("searchable-select","multi-select","radio-group","checkbox",
            "slider","duration","color","location-anchor","material","entity","script-reference","content-id-reference");

    private EditorSchemaAnnotations() {}

    /** Defensive deep copy plus validation of Persona-owned annotations. */
    static Map<String,Object> copyAndValidate(Map<String,Object> source) {
        if(source==null||source.isEmpty())return Map.of();
        Object copied=copy(source,"$");
        return Collections.unmodifiableMap(castMap(copied));
    }

    private static Object copy(Object value,String path){
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof Map<?,?> map){
            Map<String,Object> result=new LinkedHashMap<>();
            for(var entry:map.entrySet()){
                if(!(entry.getKey() instanceof String key)||key.isBlank())throw new IllegalArgumentException(path+" has a non-string or blank schema key");
                Object child=copy(entry.getValue(),path+"."+key);validateAnnotation(key,child,path);result.put(key,child);
            }
            return result;
        }
        if(value instanceof Collection<?> values){List<Object> result=new ArrayList<>();int i=0;for(Object item:values)result.add(copy(item,path+"["+(i++)+"]"));return List.copyOf(result);}
        throw new IllegalArgumentException(path+" contains a non-JSON schema value: "+value.getClass().getSimpleName());
    }

    private static void validateAnnotation(String key,Object value,String path){
        if(key.equals(WIDGET)&&(!(value instanceof String widget)||!WIDGETS.contains(widget)))
            throw new IllegalArgumentException(path+" uses an unknown "+WIDGET+" value");
        if(Set.of(CATALOG,REFERENCE_TYPE).contains(key)&&(!(value instanceof String text)||text.isBlank()))
            throw new IllegalArgumentException(path+" requires a non-blank "+key);
        if(key.equals(ORDER)&&!(value instanceof Number))throw new IllegalArgumentException(path+" requires numeric "+ORDER);
        if(key.equals(DEPENDS_ON)&&!(value instanceof String)&&!(value instanceof List<?>))
            throw new IllegalArgumentException(path+" requires a field name or list for "+DEPENDS_ON);
        if(key.equals(VALIDATION_MESSAGE)&&!(value instanceof String))throw new IllegalArgumentException(path+" requires text for "+VALIDATION_MESSAGE);
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object value){return (Map<String,Object>)value;}
}
