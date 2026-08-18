package nu.miguel.persona.content;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Copies immutable packaged examples into live content without overwriting operator files. */
public final class ExampleInstaller {
    public static final List<String> AVAILABLE=List.of("behaviors/builder-routine.yml","behaviors/keeper-festival.yml","behaviors/keeper-player.yml","behaviors/keeper-shared.yml","behaviors/private-walker.yml","dialogues/builder.yml","dialogues/builder_delivery.yml","dialogues/builder_thanks.yml","dialogues/trial_active.yml","dialogues/trial_complete.yml","dialogues/trial_intro.yml","npcs/builder.yml","npcs/harbor_keeper.yml","npcs/private-walker.yml","npcs/trial_master.yml","quests/adventurers_trial.yml","quests/supplies.yml","scripts/dramatic-warning.yml","scripts/quest-success.yml");
    private ExampleInstaller(){}
    public static Path copy(Path contentRoot,String requested)throws IOException{String name=Objects.toString(requested,"").toLowerCase(Locale.ROOT);if(!AVAILABLE.contains(name))throw new IllegalArgumentException("Unknown packaged example "+requested);Path root=contentRoot.toAbsolutePath().normalize(),target=root.resolve(name).normalize();if(!target.startsWith(root))throw new IllegalArgumentException("Example path escapes content directory");if(Files.exists(target))throw new FileAlreadyExistsException(target.toString());String resource="/examples/"+name+".example";try(InputStream input=ExampleInstaller.class.getResourceAsStream(resource)){if(input==null)throw new FileNotFoundException("Packaged example is missing: "+name);Files.createDirectories(target.getParent());Files.copy(input,target);}return target;}
}
