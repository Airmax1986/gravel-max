import btools.mapcreator.OsmFastCutter;
import btools.mapcreator.PosUnifier;
import btools.mapcreator.WayLinker;
import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import com.google.protobuf.ByteString;
import org.openstreetmap.osmosis.osmbinary.Fileformat;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Original synthetic OSM -> real BRouter RD5 -> actual RoutingEngine tests. */
public final class RouteFixtureTest {
  // A diamond with shared start/end tails. No human route data or live service.
  private static final double[][] POINTS = {
      {8.000, 52.100}, {8.002, 52.100}, {8.007, 52.102},
      {8.007, 52.098}, {8.012, 52.100}, {8.014, 52.100}};
  private static int assertions;
  private static int routes;

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("source profile work-directory");
    Path source = Path.of(args[0]);
    Path profile = Path.of(args[1]);
    Path root = Path.of(args[2]);
    Files.createDirectories(root);
    System.setProperty("avoidMapPolling", "true");
    Path profiles = root.resolve("profiles");
    Files.createDirectories(profiles);
    Path localProfile = profiles.resolve("gravel-max.brf");
    Files.copy(profile, localProfile, StandardCopyOption.REPLACE_EXISTING);
    Files.copy(source.resolve("misc/profiles2/lookups.dat"), profiles.resolve("lookups.dat"));

    File natural = build(source, root.resolve("natural"), Map.of("surface", "ground"));
    checkRoute(natural, localProfile, false, true, "natural corridor over equivalent asphalt");
    checkRoute(natural, localProfile, true, true, "natural corridor works in reverse");
    File banned = build(source, root.resolve("banned"), Map.of("surface", "ground", "bicycle", "no"));
    checkRoute(banned, localProfile, false, false, "bicycle prohibition reroutes to asphalt");
    File loose = build(source, root.resolve("loose"), Map.of("surface", "sand"));
    checkRoute(loose, localProfile, false, false, "loose sand reroutes to firm asphalt");
    File railway = build(source, root.resolve("railway"), Map.of("highway", "", "railway", "rail", "surface", "ground"));
    checkRoute(railway, localProfile, false, false, "non-road railway shortcut is rejected");
    File impassable = build(source, root.resolve("impassable"), Map.of("surface", "ground", "smoothness", "impassable"));
    checkRoute(impassable, localProfile, false, false, "impassable natural shortcut is rejected");
    File oneway = build(source, root.resolve("oneway"), Map.of("surface", "ground", "oneway", "yes"));
    checkRoute(oneway, localProfile, false, true, "one-way corridor accepted forward");
    checkRoute(oneway, localProfile, true, false, "one-way corridor rejected backward");
    File contra = build(source, root.resolve("contraflow"),
        Map.of("surface", "ground", "oneway", "yes", "oneway:bicycle", "no"));
    checkRoute(contra, localProfile, true, true, "bicycle contraflow exception routes backward");
    File reverseOnly = build(source, root.resolve("reverse-only"),
        Map.of("surface", "ground", "oneway", "-1", "oneway:bicycle", "yes", "cycleway", "opposite_lane"));
    checkRoute(reverseOnly, localProfile, false, false, "explicit bicycle reverse direction rejects forward shortcut");
    checkRoute(reverseOnly, localProfile, true, true, "explicit bicycle reverse direction remains usable");
    File preferences = build(source, root.resolve("preferences"), Map.of("surface", "ground"),
        Map.of("highway", "track", "tracktype", "grade2", "surface", "compacted"));
    checkRoute(preferences, localProfile, false, true, "natural preference beats equal gravel branch");
    checkRoute(preferences, localProfile, false, false, "disabling natural preference selects gravel branch",
        Map.of("prefer_natural_surfaces", "0"));
    System.out.println("RouteFixtureTest: " + routes + " real RD5 routes, " + assertions + " assertions passed");
  }

  private static File build(Path source, Path work, Map<String, String> upperTags) throws Exception {
    return build(source, work, upperTags, Map.of("highway", "residential", "surface", "asphalt"));
  }

  private static File build(Path source, Path work, Map<String, String> upperTags, Map<String, String> lowerTags) throws Exception {
    Files.createDirectories(work);
    Path pbf = work.resolve("fixture.pbf");
    writePbf(pbf, upperTags, lowerTags);
    File nodes = directory(work, "nodes");
    File ways = directory(work, "ways");
    File nodes55 = directory(work, "nodes55");
    File ways55 = directory(work, "ways55");
    File unified = directory(work, "unified");
    File segments = directory(work, "segments");
    File elevations = directory(work, "empty-dem");
    Path profiles = source.resolve("misc/profiles2");
    File lookups = profiles.resolve("lookups.dat").toFile();
    File borderIds = work.resolve("borderids.dat").toFile();
    File borders = work.resolve("borders.dat").toFile();
    File restrictions = work.resolve("restrictions.dat").toFile();
    File all = profiles.resolve("all.brf").toFile();
    OsmFastCutter.doCut(lookups, nodes, ways, nodes55, ways55, borderIds,
        work.resolve("relations.dat").toFile(), restrictions, all,
        profiles.resolve("trekking.brf").toFile(), profiles.resolve("softaccess.brf").toFile(),
        pbf.toFile(), null);
    new PosUnifier().process(nodes55, unified, borderIds, borders, elevations.toString(), null);
    new WayLinker().process(unified, ways55, borders, restrictions, lookups, all, segments, "rd5");
    return segments;
  }

  private static File directory(Path root, String name) throws Exception {
    return Files.createDirectories(root.resolve(name)).toFile();
  }

  private static void checkRoute(File segments, Path profile, boolean reverse, boolean upper, String label) {
    checkRoute(segments, profile, reverse, upper, label, Map.of());
  }

  private static void checkRoute(File segments, Path profile, boolean reverse, boolean upper, String label,
      Map<String, String> options) {
    RoutingContext context = new RoutingContext();
    context.localFunction = profile.toString();
    context.keyValues = new java.util.HashMap<>(options);
    List<OsmNodeNamed> waypoints = new ArrayList<>();
    waypoints.add(waypoint(reverse ? 5 : 0, "from"));
    waypoints.add(waypoint(reverse ? 0 : 5, "to"));
    RoutingEngine engine = new RoutingEngine(null, null, segments, waypoints, context);
    engine.doRun(30000);
    check(engine.getErrorMessage() == null, label + ": " + engine.getErrorMessage());
    OsmTrack track = engine.getFoundTrack();
    check(track != null && track.nodes.size() >= 3, label + ": missing track");
    check(near(track.nodes.get(0), reverse ? 5 : 0), label + ": start mismatch");
    check(near(track.nodes.get(track.nodes.size() - 1), reverse ? 0 : 5), label + ": end mismatch");
    check(track.nodes.stream().anyMatch(p -> near(p, upper ? 2 : 3)), label + ": expected branch missing");
    check(track.nodes.stream().noneMatch(p -> near(p, upper ? 3 : 2)), label + ": wrong branch included");
    // Each exported segment must lie along one of the actual synthetic ways.
    int[][] edges = {{0,1}, {1,2}, {2,4}, {1,3}, {3,4}, {4,5}};
    for (int i = 1; i < track.nodes.size(); i++) {
      OsmPathElement a = track.nodes.get(i - 1), b = track.nodes.get(i);
      boolean connected = false;
      for (int[] edge : edges) {
        if (onSegment(a, edge[0], edge[1]) && onSegment(b, edge[0], edge[1])) connected = true;
      }
      check(connected, label + ": disconnected exported geometry");
    }
    routes++;
    System.out.println("PASS: " + label);
  }

  private static boolean near(OsmPathElement p, int i) {
    return Math.abs(p.getILon() - lon(i)) < 15 && Math.abs(p.getILat() - lat(i)) < 15;
  }

  private static boolean onSegment(OsmPathElement p, int a, int b) {
    double dx = lon(b) - lon(a), dy = lat(b) - lat(a);
    double px = p.getILon() - lon(a), py = p.getILat() - lat(a);
    double t = (px * dx + py * dy) / (dx * dx + dy * dy);
    return t >= -0.005 && t <= 1.005 && Math.hypot(px - t * dx, py - t * dy) < 15;
  }

  private static int lon(int i) { return (int)Math.round((POINTS[i][0] + 180) * 1e6); }
  private static int lat(int i) { return (int)Math.round((POINTS[i][1] + 90) * 1e6); }

  private static OsmNodeNamed waypoint(int index, String name) {
    OsmNodeNamed p = new OsmNodeNamed();
    p.name = name; p.ilon = lon(index); p.ilat = lat(index);
    return p;
  }

  private static void writePbf(Path path, Map<String, String> upper, Map<String, String> lower) throws Exception {
    List<String> strings = new ArrayList<>(); strings.add("");
    Osmformat.PrimitiveGroup.Builder nodes = Osmformat.PrimitiveGroup.newBuilder();
    for (int i = 0; i < POINTS.length; i++) {
      nodes.addNodes(Osmformat.Node.newBuilder().setId(i + 1)
          .setLon(Math.round(POINTS[i][0] * 1e7)).setLat(Math.round(POINTS[i][1] * 1e7)));
    }
    Osmformat.PrimitiveGroup.Builder ways = Osmformat.PrimitiveGroup.newBuilder();
    Map<String, String> upperAll = new LinkedHashMap<>();
    upperAll.put("highway", "track"); upperAll.put("tracktype", "grade2"); upperAll.putAll(upper);
    if ("".equals(upperAll.get("highway"))) upperAll.remove("highway");
    ways.addWays(way(101, new int[]{1,2}, Map.of("highway", "residential", "surface", "asphalt"), strings));
    ways.addWays(way(102, new int[]{2,3,5}, upperAll, strings));
    ways.addWays(way(103, new int[]{2,4,5}, lower, strings));
    ways.addWays(way(104, new int[]{5,6}, Map.of("highway", "residential", "surface", "asphalt"), strings));
    Osmformat.StringTable.Builder table = Osmformat.StringTable.newBuilder();
    for (String s : strings) table.addS(ByteString.copyFromUtf8(s));
    Osmformat.PrimitiveBlock block = Osmformat.PrimitiveBlock.newBuilder().setStringtable(table)
        .setGranularity(100).addPrimitivegroup(nodes).addPrimitivegroup(ways).build();
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
      blob(out, "OSMHeader", Osmformat.HeaderBlock.newBuilder().addRequiredFeatures("OsmSchema-V0.6").build().toByteArray());
      blob(out, "OSMData", block.toByteArray());
    }
  }

  private static Osmformat.Way way(int id, int[] refs, Map<String, String> tags, List<String> strings) {
    Osmformat.Way.Builder way = Osmformat.Way.newBuilder().setId(id);
    int previous = 0;
    for (int ref : refs) { way.addRefs(ref - previous); previous = ref; }
    tags.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      if (!strings.contains(entry.getKey())) strings.add(entry.getKey());
      if (!strings.contains(entry.getValue())) strings.add(entry.getValue());
      way.addKeys(strings.indexOf(entry.getKey())).addVals(strings.indexOf(entry.getValue()));
    });
    return way.build();
  }

  private static void blob(DataOutputStream out, String type, byte[] bytes) throws Exception {
    byte[] body = Fileformat.Blob.newBuilder().setRaw(ByteString.copyFrom(bytes)).build().toByteArray();
    byte[] header = Fileformat.BlobHeader.newBuilder().setType(type).setDatasize(body.length).build().toByteArray();
    out.writeInt(header.length); out.write(header); out.write(body);
  }

  private static void check(boolean ok, String message) {
    assertions++;
    if (!ok) throw new AssertionError(message);
  }
}
