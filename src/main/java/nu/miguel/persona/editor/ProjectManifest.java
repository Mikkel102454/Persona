package nu.miguel.persona.editor;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Strict parser for shared empty-folder metadata. Runtime content never reads this manifest. */
final class ProjectManifest {
    static final String PATH=".persona/project.yml";
    static final Set<String> ROOTS=Set.of("npcs","dialogues","quests","behaviors","scripts");
    private ProjectManifest(){}

    static Set<String> read(Path root)throws IOException{
        Path file=root.resolve(PATH);if(!Files.isRegularFile(file))return Set.of();
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file.toFile());
        Set<String> keys=yaml.getKeys(false);if(!Set.of("version","folders").containsAll(keys))throw new IOException(".persona/project.yml contains unsupported keys");
        Object version=yaml.get("version");if(!(version instanceof Number number)||number.doubleValue()!=1.0)throw new IOException(".persona/project.yml requires version: 1");
        Object raw=yaml.get("folders");if(!(raw instanceof List<?> list))throw new IOException(".persona/project.yml folders must be a list");
        TreeSet<String> folders=new TreeSet<>();Map<String,String> folded=new HashMap<>();
        for(Object item:list){String folder=Objects.toString(item,"");validate(folder);String collision=folded.putIfAbsent(folder.toLowerCase(Locale.ROOT),folder);if(collision!=null)throw new IOException("folder paths collide by case: "+collision+" and "+folder);folders.add(folder);}
        return Set.copyOf(folders);
    }

    static void validate(String folder)throws IOException{
        if(folder.isBlank()||folder.length()>236||folder.startsWith("/")||folder.contains("\\")||folder.endsWith("/"))throw new IOException("invalid project folder path "+folder);
        String[] parts=folder.split("/",-1);if(parts.length<2||parts.length>9||!ROOTS.contains(parts[0]))throw new IOException("folder must be beneath a fixed Persona kind root with at most eight subfolders: "+folder);
        for(int index=1;index<parts.length;index++)if(!parts[index].matches("[a-z0-9][a-z0-9._-]*"))throw new IOException("invalid folder segment "+parts[index]);
    }
}
