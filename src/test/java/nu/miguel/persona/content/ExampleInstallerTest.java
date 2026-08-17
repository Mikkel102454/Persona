package nu.miguel.persona.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExampleInstallerTest {
    @TempDir Path root;
    @Test void copiesOnlyKnownPackagedExamplesAndNeverOverwrites()throws Exception{Path copied=ExampleInstaller.copy(root,"behaviors/private-walker.yml");assertTrue(Files.readString(copied).contains("id: persona:private-walker-demo"));assertThrows(FileAlreadyExistsException.class,()->ExampleInstaller.copy(root,"behaviors/private-walker.yml"));assertThrows(IllegalArgumentException.class,()->ExampleInstaller.copy(root,"../config.yml"));}
    @Test void manifestMatchesEveryPackagedExample(){for(String path:ExampleInstaller.AVAILABLE)assertNotNull(ExampleInstaller.class.getResource("/examples/"+path+".example"),path);}
}
