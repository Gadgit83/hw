import party.hedge.host.GameConfig;
import java.io.File;
import java.util.*;
public class GenConfig {
  public static void main(String[] a) throws Exception {
    List<String> themes = new ArrayList<>();
    for (String t : new File(a[1]).list()) if (new File(a[1], t + "/theme.cfg").isFile()) themes.add(t);
    String[] teams = a.length > 3 ? Arrays.copyOfRange(a, 3, a.length) : new String[]{"Red Team","Blue Team"};
    GameConfig.write(new File(a[0]), GameConfig.build(teams, themes, Long.parseLong(a.length > 2 ? a[2] : "12345")));
  }
}
