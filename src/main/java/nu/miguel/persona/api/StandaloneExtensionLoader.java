package nu.miguel.persona.api;

import nu.miguel.persona.Main;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.URLClassLoader;
import java.util.*;

/** Startup-only loader for trusted standalone Persona extension jars. */
public final class StandaloneExtensionLoader implements AutoCloseable {
    private final Main plugin;private final List<URLClassLoader> loaders=new ArrayList<>();private final List<PersonaExpansion> expansions=new ArrayList<>();
    public StandaloneExtensionLoader(Main plugin){this.plugin=plugin;}
    public void loadAll(){File directory=new File(plugin.getDataFolder(),"extensions");if(!directory.exists()&&!directory.mkdirs()){plugin.getLogger().warning("Could not create extensions directory");return;}File[] jars=directory.listFiles(f->f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));if(jars==null)return;Arrays.sort(jars,Comparator.comparing(File::getName));Set<String> ids=new HashSet<>();for(File jar:jars)try{URLClassLoader loader=new URLClassLoader(new java.net.URL[]{jar.toURI().toURL()},plugin.getClass().getClassLoader());var resource=loader.getResource("persona-extension.yml");if(resource==null)throw new IllegalArgumentException("missing persona-extension.yml");YamlConfiguration manifest=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(resource.openStream(),java.nio.charset.StandardCharsets.UTF_8));String main=required(manifest.getString("main"),"main"),id=required(manifest.getString("id"),"id").toLowerCase(Locale.ROOT),version=required(manifest.getString("version"),"version"),api=required(manifest.getString("api-version"),"api-version");if(!ids.add(id))throw new IllegalArgumentException("duplicate extension id "+id);Object instance=Class.forName(main,true,loader).getDeclaredConstructor().newInstance();if(!(instance instanceof PersonaExpansion expansion))throw new IllegalArgumentException(main+" does not extend PersonaExpansion");if(!expansion.identifier().equalsIgnoreCase(id))throw new IllegalArgumentException("manifest id does not match expansion identifier");if(!expansion.version().equals(version))plugin.getLogger().warning("Extension "+id+" manifest and class versions differ");if(!expansion.requiredApiVersion().equals(api))throw new IllegalArgumentException("manifest api-version does not match expansion");if(!expansion.register())throw new IllegalArgumentException("extension rejected compatibility check");loaders.add(loader);expansions.add(expansion);new File(plugin.getDataFolder(),"extensions-data/"+id).mkdirs();plugin.getLogger().info("Loaded Persona extension "+id+" "+version);}catch(Exception e){plugin.getLogger().severe("Could not load extension "+jar.getName()+": "+e.getMessage());}}
    private static String required(String value,String field){if(value==null||value.isBlank())throw new IllegalArgumentException("missing "+field);return value;}
    public void close(){ListIterator<PersonaExpansion> it=expansions.listIterator(expansions.size());while(it.hasPrevious())try{it.previous().unregister();}catch(RuntimeException ignored){}for(URLClassLoader loader:loaders)try{loader.close();}catch(Exception ignored){}expansions.clear();loaders.clear();}
}
