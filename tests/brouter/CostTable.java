import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints the real BRouter cost of each archetype: way costfactor or node initialcost.
 * Without variants: "label<TAB>cost". With variants ("default" or "key=value[,key=value]",
 * applied to every row beneath the row's own opt: tokens): "variant<TAB>label<TAB>cost".
 */
public final class CostTable {
  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      throw new IllegalArgumentException("usage: CostTable <profile.brf> <lookups.dat> <archetypes.txt> [variant ...]");
    }
    File profile = new File(args[0]);
    File lookups = new File(args[1]);
    List<String> lines = Files.readAllLines(new File(args[2]).toPath());
    Map<String, Object[]> contexts = new HashMap<>();
    int rows = 0;
    if (args.length == 3) {
      rows = print(profile, lookups, lines, new TreeMap<>(), "", contexts);
    } else {
      for (int i = 3; i < args.length; i++) {
        Map<String, String> base = new TreeMap<>();
        if (!args[i].equals("default")) {
          for (String option : args[i].split(",")) {
            String[] pair = option.split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("variant needs key=value: " + args[i]);
            base.put(pair[0], pair[1]);
          }
        }
        rows += print(profile, lookups, lines, base, args[i] + "\t", contexts);
      }
    }
    System.err.println("CostTable: " + rows + " rows");
  }

  private static int print(File profile, File lookups, List<String> lines, Map<String, String> base, String prefix,
      Map<String, Object[]> contexts) {
    int rows = 0;
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int bar = line.indexOf('|');
      if (bar <= 0) throw new IllegalArgumentException("archetype needs 'label | tags': " + line);
      String label = line.substring(0, bar).strip();
      boolean node = false, inverse = false;
      Map<String, String> options = new TreeMap<>(base);
      List<String> tags = new ArrayList<>();
      for (String token : line.substring(bar + 1).strip().split("\\s+")) {
        if (token.equals("NODE")) node = true;
        else if (token.equals("REV")) inverse = true;
        else if (token.startsWith("opt:")) {
          String[] option = token.substring(4).split("=", 2);
          options.put(option[0], option[1]);
        } else tags.add(token);
      }
      // Each distinct option set needs its own parse: options are injected at parse time.
      Object[] pair = contexts.computeIfAbsent(options.toString(), key -> load(profile, lookups, options));
      float cost;
      if (node) {
        BExpressionContextNode context = (BExpressionContextNode) pair[1];
        context.evaluate(false, encode(context, label, tags));
        cost = context.getInitialcost();
      } else {
        BExpressionContextWay context = (BExpressionContextWay) pair[0];
        context.evaluate(inverse, encode(context, label, tags));
        cost = context.getCostfactor();
      }
      System.out.println(prefix + label + "\t" + String.format(Locale.ROOT, "%.4f", cost));
      rows++;
    }
    return rows;
  }

  private static Object[] load(File profile, File lookups, Map<String, String> options) {
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay way = new BExpressionContextWay(meta);
    BExpressionContextNode node = new BExpressionContextNode(meta);
    node.setForeignContext(way);
    meta.readMetaData(lookups);
    way.parseFile(profile, "global", new HashMap<>(options));
    node.parseFile(profile, "global", new HashMap<>(options));
    return new Object[] {way, node};
  }

  private static byte[] encode(BExpressionContext context, String label, List<String> tags) {
    int[] values = context.createNewLookupData();
    for (String tag : tags) {
      int delimiter = tag.indexOf('=');
      if (delimiter <= 0) throw new IllegalArgumentException(label + ": invalid OSM tag " + tag);
      int[] before = values.clone();
      context.addLookupValue(tag.substring(0, delimiter), tag.substring(delimiter + 1), values);
      // An unknown key is silently dropped and an unknown value becomes index 1 ("unknown");
      // either would make the row describe a different way than its label says.
      boolean encoded = false;
      for (int i = 0; i < values.length; i++) {
        if (values[i] != before[i]) encoded = values[i] > 1;
      }
      if (!encoded) throw new IllegalArgumentException(label + ": tag not in lookup table: " + tag);
    }
    return context.encode(values);
  }
}
