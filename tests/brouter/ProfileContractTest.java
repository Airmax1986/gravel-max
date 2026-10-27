import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Contract tests against BRouter's real BRF expression evaluator and lookups. */
public final class ProfileContractTest {
  private static int assertions;

  public static void main(String[] args) {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: ProfileContractTest <profile.brf> <lookups.dat>");
    }
    File profile = new File(args[0]);
    File lookups = new File(args[1]);
    Contexts defaults = load(profile, lookups, Map.of());
    BExpressionContextWay way = defaults.way;
    BExpressionContextNode node = defaults.node;

    float natural = cost(way, "highway=track", "tracktype=grade2", "surface=ground");
    float gravel = cost(way, "highway=track", "tracktype=grade2", "surface=compacted");
    float paved = cost(way, "highway=track", "tracktype=grade2", "surface=asphalt");
    float unknown = cost(way, "highway=track", "tracktype=grade2");
    check(natural < gravel && gravel < paved, "natural then gravel then paved surface preference");
    check(unknown > gravel && unknown < paved, "missing surface remains neutral, not inferred as gravel");
    float unpaved = cost(way, "highway=track", "tracktype=grade2", "surface=unpaved");
    check(unpaved > cost(way, "highway=track", "tracktype=grade2", "surface=gravel") && unpaved < unknown,
        "generic unpaved sits between tagged gravel and an unknown surface (" + unpaved + ")");
    check(cost(way, "highway=track", "surface=sand") > paved, "loose sand costs more than firm pavement");
    near(cost(way, "highway=residential"), cost(way, "highway=residential", "surface=asphalt"),
        "untagged residential road costs as asphalt");
    near(cost(way, "highway=tertiary"), cost(way, "highway=tertiary", "surface=asphalt"),
        "untagged tertiary road costs as asphalt");
    near(cost(way, "highway=cycleway"), cost(way, "highway=cycleway", "surface=asphalt"),
        "untagged cycleway costs as asphalt");
    check(cost(way, "highway=track", "tracktype=grade1") >= 1.32f,
        "untagged grade1 track costs at least the bare asphalt surface factor 1.32");
    near(unknown, 1.20f, "untagged grade2 track keeps the neutral unknown-surface cost");
    check(cost(way, "highway=track", "surface=gravel", "smoothness=bad") >
        cost(way, "highway=track", "surface=gravel", "smoothness=good"), "poor smoothness is penalized");
    near(cost(way, "highway=track", "tracktype=grade2", "surface=gravel", "smoothness=bad") /
        cost(way, "highway=track", "tracktype=grade2", "surface=gravel"), 1.35f,
        "smoothness=bad on gravel is a moderate penalty");
    near(cost(way, "highway=track", "tracktype=grade2", "surface=asphalt", "smoothness=bad") /
        cost(way, "highway=track", "tracktype=grade2", "surface=asphalt"), 1.75f,
        "smoothness=bad on asphalt keeps the damage penalty");
    check(cost(way, "highway=track", "tracktype=grade2", "surface=ground", "smoothness=bad") <
        cost(way, "highway=track", "tracktype=grade2", "surface=asphalt", "smoothness=bad"),
        "bad natural track stays cheaper than bad asphalt track");
    check(cost(way, "highway=track", "surface=gravel", "tracktype=grade5") >
        cost(way, "highway=track", "surface=gravel", "tracktype=grade1"), "tracktype is rideability, not surface");
    BExpressionContextWay noNatural = load(profile, lookups, Map.of("prefer_natural_surfaces", "0")).way;
    near(cost(noNatural, "highway=track", "surface=ground"),
        cost(noNatural, "highway=track"), "natural-surface option disables the natural preference");
    BExpressionContextWay noGravel = load(profile, lookups, Map.of("prefer_gravel_surfaces", "0")).way;
    near(cost(noGravel, "highway=track", "surface=compacted"),
        cost(noGravel, "highway=track"), "gravel-surface option disables the gravel preference");

    float easyPath = cost(way, "highway=path", "surface=ground", "mtb:scale=0");
    float hardPath = cost(way, "highway=path", "surface=ground", "mtb:scale=3");
    check(hardPath > easyPath, "technical MTB scale is penalized");
    check(cost(way, "highway=path", "surface=ground", "mtb:scale=1+") > easyPath &&
        cost(way, "highway=path", "surface=ground", "mtb:scale=1+") < hardPath,
        "mtb:scale=1+ remains an intermediate difficulty");
    BExpressionContextWay trailWay = load(profile, lookups, Map.of("prefer_singletrails", "1")).way;
    check(cost(trailWay, "highway=path", "surface=ground", "mtb:scale=0") < easyPath,
        "singletrail option changes easy path preference");
    check(cost(way, "highway=residential", "surface=asphalt") <
        cost(way, "highway=primary", "surface=asphalt"), "quiet local asphalt remains a connector");
    near(cost(way, "highway=residential", "surface=asphalt"),
        cost(way, "highway=residential", "surface=asphalt", "bicycle=yes"),
        "missing bicycle tag does not reduce scenic desirability");
    check(cost(way, "highway=tertiary", "surface=asphalt") <
        cost(way, "highway=primary", "surface=asphalt"), "major roads cost more than tertiary roads");
    near(cost(way, "highway=tertiary", "surface=asphalt", "maxspeed=70") /
        cost(way, "highway=tertiary", "surface=asphalt"), 1.15f, "maxspeed 70 raises a tertiary road's cost");
    near(cost(way, "highway=secondary", "surface=asphalt", "maxspeed=rural") /
        cost(way, "highway=secondary", "surface=asphalt"), 1.5f, "rural speed limit raises a secondary road's cost");
    near(cost(way, "highway=residential", "surface=asphalt", "maxspeed=70"),
        cost(way, "highway=residential", "surface=asphalt"), "maxspeed does not change local streets");
    check(cost(way, "highway=residential", "surface=asphalt", "estimated_traffic_class=7") >
        cost(way, "highway=residential", "surface=asphalt", "estimated_traffic_class=1"),
        "stock traffic estimate raises cost");
    BExpressionContextWay noTrafficWay = load(profile, lookups, Map.of("consider_traffic_estimate", "0")).way;
    near(cost(noTrafficWay, "highway=residential", "surface=asphalt", "estimated_traffic_class=7"),
        cost(noTrafficWay, "highway=residential", "surface=asphalt", "estimated_traffic_class=1"),
        "traffic estimate option disables traffic multiplier");

    blocked(cost(way, "highway=track", "access=no"), "generic access=no blocks way");
    blocked(cost(way, "highway=trunk", "surface=asphalt"), "trunk roads are excluded");
    blocked(cost(way, "highway=trunk_link", "surface=asphalt"), "trunk links are excluded");
    blocked(cost(way, "waterway=river"), "waterway without a routable highway is excluded");
    blocked(cost(way, "railway=rail"), "railway without a routable highway is excluded");
    for (String unsuitable : new String[] {"platform", "raceway", "corridor"}) {
      blocked(cost(way, "highway=" + unsuitable), "unsuitable highway=" + unsuitable + " is excluded");
    }
    blocked(cost(way, "highway=track", "surface=gravel", "smoothness=impassable"),
        "impassable smoothness blocks public routing");
    blocked(cost(way, "highway=path", "surface=ground", "sac_scale=demanding_mountain_hiking"),
        "demanding hiking terrain is excluded for gravel routing");
    blocked(cost(way, "highway=track", "vehicle=private"), "vehicle private blocks way");
    blocked(cost(way, "highway=track", "bicycle=private"), "bicycle private blocks way");
    blocked(cost(way, "highway=track", "bicycle=use_sidepath"), "bicycle use_sidepath is not routed on this way");
    check(cost(way, "highway=path", "surface=ground", "bicycle=dismount") >
        cost(way, "highway=path", "surface=ground"), "bicycle dismount remains possible at high cost");
    blocked(cost(way, "highway=track", "bicycle=no", "route_bicycle_rcn=yes"),
        "cycle relation cannot override explicit bicycle ban");
    check(cost(way, "highway=track", "access=no", "bicycle=yes") < 9999,
        "explicit bicycle permission overrides generic access ban");
    check(cost(way, "highway=track", "vehicle=no", "bicycle=designated") < 9999,
        "specific bicycle designation overrides vehicle ban");
    check(cost(way, "highway=track", "access=destination") < 9999,
        "destination access remains routable");

    float forwardOneway = cost(way, false, "highway=residential", "oneway=yes");
    float reverseOneway = cost(way, true, "highway=residential", "oneway=yes");
    check(forwardOneway < 9999 && reverseOneway >= 9999, "oneway=yes is directional");
    check(cost(way, true, "highway=residential", "oneway=yes", "oneway:bicycle=no") < 9999,
        "oneway:bicycle=no permits reverse cycling");
    check(cost(way, true, "highway=residential", "oneway=yes", "cycleway=opposite_lane") < 9999,
        "opposite cycleway permits contraflow");
    check(cost(way, false, "highway=residential", "oneway=-1") >= 9999 &&
        cost(way, true, "highway=residential", "oneway=-1") < 9999, "oneway=-1 direction is reversed");
    blocked(cost(way, "highway=residential", "motorroad=yes", "vehicle=yes"),
        "motorroad default is not reopened by generic vehicle=yes");
    blocked(cost(way, "highway=residential", "motorroad=yes", "bicycle=mtb"),
        "unrecognized bicycle tag does not reopen motorroad");
    check(cost(way, true, "highway=residential", "junction=roundabout") >= 9999,
        "roundabout is one-way by default");
    for (String generic : new String[]{"yes", "true", "1", "-1"}) {
      boolean reverseAllowed = generic.equals("-1");
      for (boolean reverse : new boolean[]{false, true}) {
        String[] directionTags = {"highway=residential", "oneway=" + generic, "oneway:bicycle=yes"};
        check((cost(way, reverse, directionTags) < 9999) == (reverse == reverseAllowed),
            "bicycle one-way follows generic " + generic + " reverse=" + reverse);
        check((cost(way, reverse, "highway=residential", "oneway=" + generic,
            "oneway:bicycle=yes", "cycleway=opposite_lane") < 9999) == (reverse == reverseAllowed),
            "explicit bicycle direction wins over cycleway hint " + generic + " reverse=" + reverse);
      }
    }

    blocked(cost(way, "highway=steps", "surface=paving_stones"), "steps are excluded by default");
    BExpressionContextWay stepsWay = load(profile, lookups, Map.of("allow_steps", "1")).way;
    check(cost(stepsWay, "highway=steps", "surface=paving_stones") < 9999,
        "allow_steps option opens steps at high cost");
    check(cost(way, "route=ferry") < 9999, "ferries are allowed by default");
    BExpressionContextWay noFerryWay = load(profile, lookups, Map.of("allow_ferries", "0")).way;
    blocked(cost(noFerryWay, "route=ferry"), "allow_ferries option blocks ferries");

    // Elevation evidence: the router reads climb costs per way through the built-in
    // getters (StdPath.processWaySection). They are read here the same way:
    // getVariableValue() returns stale data when the evaluation comes from BRouter's
    // result cache. Natural, gravel and unpaved ways climb at 20/8; paved, unknown and
    // loose/muddy ways keep 55/18.
    for (String[] row : CLIMB_CASES) {
      Map<String, String> options = new HashMap<>();
      java.util.List<String> tagList = new java.util.ArrayList<>();
      for (String token : java.util.Arrays.copyOfRange(row, 2, row.length)) {
        if (token.startsWith("opt:")) options.put(token.substring(4).split("=", 2)[0], token.substring(4).split("=", 2)[1]);
        else tagList.add(token);
      }
      BExpressionContextWay climbWay = options.isEmpty() ? way : load(profile, lookups, options).way;
      String[] tags = tagList.toArray(new String[0]);
      String what = String.join(" ", java.util.Arrays.copyOfRange(row, 2, row.length));
      cost(climbWay, tags);
      near(climbWay.getUphillcost(), Float.parseFloat(row[0]), "uphillcost for " + what);
      near(climbWay.getDownhillcost(), Float.parseFloat(row[1]), "downhillcost for " + what);
      near(climbWay.getUphillcutoff(), 2f, "uphillcutoff for " + what);
      near(climbWay.getDownhillcutoff(), 2f, "downhillcutoff for " + what);
    }

    near(cost(way, "highway=track", "tracktype=grade2", "surface=cobblestone"), 1.6f * unknown / 1.20f,
        "cobblestone costs more than other paved surfaces");
    check(cost(way, "highway=track", "tracktype=grade2", "surface=ground", "mtb:scale=2") >
        cost(way, "highway=track", "tracktype=grade2", "surface=ground"), "mtb:scale also marks technical tracks");
    near(unknown, cost(way, "highway=track", "tracktype=grade2", "mtb:scale=0"),
        "untagged-path default does not spread to tracks");
    float residentialAsphalt = cost(way, "highway=residential", "surface=asphalt");
    near(cost(way, "highway=residential", "surface=asphalt", "oneway=reversible"), 1.3f * residentialAsphalt,
        "reversible road stays routable but is not preferred");
    near(cost(way, true, "highway=residential", "surface=asphalt", "oneway=reversible"), 1.3f * residentialAsphalt,
        "reversible road costs the same in reverse");
    near(cost(way, "highway=residential", "surface=asphalt", "estimated_noise_class=5"), residentialAsphalt,
        "noise estimate is off by default");
    BExpressionContextWay noiseWay = load(profile, lookups, Map.of("consider_noise", "1")).way;
    check(cost(noiseWay, "highway=residential", "surface=asphalt", "estimated_noise_class=5") >
        cost(noiseWay, "highway=residential", "surface=asphalt"), "consider_noise penalises noisy ways");
    float gravelGrade2 = cost(way, "highway=track", "tracktype=grade2", "surface=gravel");
    near(cost(way, "highway=track", "tracktype=grade2", "surface=gravel", "estimated_forest_class=1"), gravelGrade2,
        "forest estimate is off by default");
    BExpressionContextWay forestWay = load(profile, lookups, Map.of("consider_forest", "1")).way;
    float forest6 = cost(forestWay, "highway=track", "tracktype=grade2", "surface=gravel", "estimated_forest_class=6");
    check(cost(forestWay, "highway=track", "tracktype=grade2", "surface=gravel", "estimated_forest_class=1") > forest6,
        "consider_forest penalises open land");
    near(forest6, gravelGrade2, "consider_forest never rewards forest below the plain cost");
    // No forest class = green factor < 0.1 = open land (BRouter environmental pseudo tags).
    near(cost(forestWay, "highway=track", "tracktype=grade2", "surface=gravel"), 1.08f * gravelGrade2,
        "consider_forest treats a missing forest class as open land");
    for (String klass : new String[] {"", "1", "2", "3", "4", "5", "6"}) {
      String forestTag = "estimated_forest_class=" + klass;
      String noiseTag = "estimated_noise_class=" + klass;
      String[] forestTags = klass.isEmpty() ? new String[] {"highway=track", "tracktype=grade2", "surface=gravel"}
          : new String[] {"highway=track", "tracktype=grade2", "surface=gravel", forestTag};
      String[] noiseTags = klass.isEmpty() ? new String[] {"highway=residential", "surface=asphalt"}
          : new String[] {"highway=residential", "surface=asphalt", noiseTag};
      check(cost(forestWay, forestTags) >= cost(way, forestTags),
          "consider_forest never goes below the plain cost: class '" + klass + "'");
      check(cost(noiseWay, noiseTags) >= cost(way, noiseTags),
          "consider_noise never goes below the plain cost: class '" + klass + "'");
    }

    float kerb = nodeCost(node, "barrier=kerb");
    float gate = nodeCost(node, "barrier=gate");
    float genericBarrier = nodeCost(node, "barrier=yes");
    float log = nodeCost(node, "barrier=log");
    check(0 < kerb && kerb < gate && gate < genericBarrier && genericBarrier < log
        && log < nodeCost(node, "barrier=wall"), "barrier costs rise from kerb to log to wall");
    check(log < 1000000, "a fallen log is costly but passable");
    blocked(nodeCost(node, "barrier=wall"), "wall blocks node");
    check(nodeCost(node, "barrier=bollard") > 100, "bollard has high node cost");
    blocked(nodeCost(node, true, "access=no"),
        "cycle route relation cannot override node access ban");
    blocked(nodeCost(node, true, "bicycle=private"),
        "cycle route relation cannot override node bicycle ban");
    blocked(nodeCost(node, true, "barrier=wall"), "cycle relation cannot open a physical wall");
    check(nodeCost(node, "access=no", "bicycle=yes") < 1000000,
        "specific bicycle permission overrides generic node access ban");

    System.out.println("ProfileContractTest: " + assertions + " assertions passed");
  }

  /** Per-way climb costs: {uphillcost, downhillcost, tags...}. Missing surface is never relieved. */
  static final String[][] CLIMB_CASES = {
      {"20", "8", "highway=track", "tracktype=grade2", "surface=ground"},
      {"20", "8", "highway=track", "surface=grass"},
      {"20", "8", "highway=path", "surface=dirt"},
      {"20", "8", "highway=track", "surface=earth"},
      {"20", "8", "highway=track", "tracktype=grade2", "surface=gravel"},
      {"20", "8", "highway=track", "surface=fine_gravel"},
      {"20", "8", "highway=cycleway", "surface=compacted"},
      {"20", "8", "highway=track", "tracktype=grade2", "surface=unpaved"},
      {"55", "18", "highway=residential", "surface=asphalt"},
      {"55", "18", "highway=residential"},
      {"55", "18", "highway=track", "tracktype=grade2"},
      {"55", "18", "highway=path"},
      {"55", "18", "highway=path", "surface=paving_stones"},
      {"55", "18", "highway=track", "surface=sand"},
      {"55", "18", "highway=track", "surface=mud"},
      // The relief follows the surface preferences; unpaved (gravel or dirt) needs both.
      {"55", "18", "highway=track", "surface=ground", "opt:prefer_natural_surfaces=0"},
      {"20", "8", "highway=track", "surface=gravel", "opt:prefer_natural_surfaces=0"},
      {"55", "18", "highway=track", "surface=unpaved", "opt:prefer_natural_surfaces=0"},
      {"55", "18", "highway=track", "surface=gravel", "opt:prefer_gravel_surfaces=0"},
      {"55", "18", "highway=cycleway", "surface=compacted", "opt:prefer_gravel_surfaces=0"},
      {"20", "8", "highway=track", "surface=ground", "opt:prefer_gravel_surfaces=0"},
      {"55", "18", "highway=track", "surface=unpaved", "opt:prefer_gravel_surfaces=0"}};

  private static Contexts load(File profile, File lookups, Map<String, String> options) {
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay context = new BExpressionContextWay(meta);
    BExpressionContextNode node = new BExpressionContextNode(meta);
    node.setForeignContext(context);
    meta.readMetaData(lookups);
    context.parseFile(profile, "global", new HashMap<>(options));
    node.parseFile(profile, "global", new HashMap<>(options));
    return new Contexts(context, node);
  }

  private static final class Contexts {
    final BExpressionContextWay way;
    final BExpressionContextNode node;

    Contexts(BExpressionContextWay way, BExpressionContextNode node) {
      this.way = way;
      this.node = node;
    }
  }

  private static float cost(BExpressionContextWay context, String... tags) {
    return cost(context, false, tags);
  }

  private static float cost(BExpressionContextWay context, boolean inverse, String... tags) {
    byte[] description = encode(context, tags);
    context.evaluate(inverse, description);
    return context.getCostfactor();
  }

  private static float nodeCost(BExpressionContextNode context, String... tags) {
    return nodeCost(context, false, tags);
  }

  private static float nodeCost(BExpressionContextNode context, boolean accessGranted, String... tags) {
    byte[] description = encode(context, tags);
    context.evaluate(accessGranted, description);
    return context.getInitialcost();
  }

  private static byte[] encode(btools.expressions.BExpressionContext context, String... tags) {
    int[] values = context.createNewLookupData();
    for (String tag : tags) {
      int delimiter = tag.indexOf('=');
      if (delimiter <= 0) throw new IllegalArgumentException("invalid OSM tag: " + tag);
      context.addLookupValue(tag.substring(0, delimiter), tag.substring(delimiter + 1), values);
    }
    return context.encode(values);
  }

  private static void blocked(float cost, String message) {
    check(cost >= 9999, message + " (cost=" + cost + ")");
  }

  private static void near(float actual, float expected, String message) {
    check(Math.abs(actual - expected) < 0.0001f, message + " (" + actual + " != " + expected + ")");
  }

  private static void check(boolean condition, String message) {
    assertions++;
    if (!condition) throw new AssertionError(message);
  }
}
