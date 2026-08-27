package org.jahia.modules.visibility.migrator;

import org.jahia.osgi.FrameworkService;
import org.jahia.services.visibility.VisibilityConditionRule;
import org.jahia.services.visibility.VisibilityService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * The operations behind the migration.
 * <p>
 * Bundle operations act on the local framework only; definition changes are made on the processing
 * node and reach the rest of the cluster through the shared CND rows.
 */
final class MigrationSupport {

    static final String TARGET = "jcontent";
    static final List<String> SOURCES = Arrays.asList("advanced-visibility", "visibility");
    static final List<String> CONDITION_TYPES = Arrays.asList(
            "jnt:timeOfDayCondition", "jnt:dayOfWeekCondition", "jnt:startEndDateCondition");

    private static final String DEFINITIONS_ENTRY = "/META-INF/definitions.cnd";
    private static final String MARKER_TYPE = "jnt:startEndDateCondition";

    private static final Logger logger = LoggerFactory.getLogger(MigrationSupport.class);

    private MigrationSupport() {
    }

    /**
     * The source bundles still present on this node. Bundles are enumerated rather than looked up by
     * symbolic name because a superseded revision lingers in {@code getBundles()} after an upgrade,
     * and a lookup by name can hand that one back instead of the live one.
     */
    static List<Bundle> findLiveSources(BundleContext context) {
        List<Bundle> live = new ArrayList<>();
        for (Bundle bundle : context.getBundles()) {
            if (SOURCES.contains(bundle.getSymbolicName()) && bundle.getState() != Bundle.UNINSTALLED) {
                live.add(bundle);
            }
        }
        return live;
    }

    static Bundle findActiveTarget(BundleContext context) {
        for (Bundle bundle : context.getBundles()) {
            if (TARGET.equals(bundle.getSymbolicName()) && bundle.getState() == Bundle.ACTIVE) {
                return bundle;
            }
        }
        return null;
    }

    /**
     * Whether this bundle declares the condition node types, read straight off its CND.
     * <p>
     * Only jContent 3.7.1 and later declare them, and a version comparison cannot express that
     * safely: {@code ModuleVersion} follows Maven ordering, so "3.7.1-SNAPSHOT" sorts <em>below</em>
     * "3.7.1" and a version floor would skip every snapshot build. The CND is readable from the
     * INSTALLED state, without resolving the bundle or loading any of its classes.
     */
    static boolean declaresConditionTypes(Bundle bundle) {
        URL definitions = bundle.getEntry(DEFINITIONS_ENTRY);
        if (definitions == null) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(definitions.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(MARKER_TYPE)) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.warn("Cannot read {} of bundle {}; treating it as not carrying the condition node types",
                    DEFINITIONS_ENTRY, bundle.getSymbolicName(), e);
        }
        return false;
    }

    /**
     * Uninstalls the source bundles on this node, and only on this node.
     * <p>
     * Deliberately not {@code ModuleManager.uninstall}: called from a bundle event, this runs on the
     * thread applying that bundle operation, and a clustered operation issued from there parks on a
     * completion latch only that same thread could release. Every node applies the install itself
     * and so fires its own event, which is what lets a purely local uninstall converge the cluster.
     */
    static int uninstallSourcesLocally(BundleContext context) {
        int uninstalled = 0;
        for (Bundle bundle : findLiveSources(context)) {
            String name = bundle.getSymbolicName();
            String version = bundle.getVersion().toString();
            long start = System.currentTimeMillis();
            try {
                bundle.stop();
                bundle.uninstall();
                uninstalled++;
                logger.info("Uninstalled {} v{} on this node in {} ms", name, version,
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                logger.error("Cannot uninstall {} v{} on this node; the condition rules may be dropped "
                        + "when it is next stopped or removed", name, version, e);
            }
        }
        return uninstalled;
    }

    /**
     * Re-registers the target module's condition rules after something unregistered them.
     * <p>
     * Needed because the core removes a condition by node type name alone, so tearing down a source
     * module drops whichever rule holds that name - the target's included. The target's services are
     * still published at that point; only the registry entries are gone.
     * <p>
     * Only the target's own rules are restored. The source modules use the same fully qualified
     * class names for their rules, so the publishing bundle is the only thing that distinguishes
     * them, and restoring a source rule here would defeat the migration.
     */
    static int reregisterTargetRules() {
        BundleContext systemContext = FrameworkService.getBundleContext();
        int restored = 0;
        try {
            Collection<ServiceReference<VisibilityConditionRule>> references =
                    systemContext.getServiceReferences(VisibilityConditionRule.class, null);
            for (ServiceReference<VisibilityConditionRule> reference : references) {
                VisibilityConditionRule rule = systemContext.getService(reference);
                if (rule == null) {
                    continue;
                }
                try {
                    if (CONDITION_TYPES.contains(rule.getAssociatedNodeType()) && publishedByTarget(rule)) {
                        VisibilityService.getInstance().addCondition(rule.getAssociatedNodeType(), rule);
                        restored++;
                        logger.info("Re-registered condition rule {} for {}", rule.getClass().getName(),
                                rule.getAssociatedNodeType());
                    }
                } finally {
                    // getService() bumps the use count, and this runs again on every source removal.
                    systemContext.ungetService(reference);
                }
            }
        } catch (InvalidSyntaxException e) {
            logger.error("Cannot look up the published condition rules", e);
        }
        return restored;
    }

    private static boolean publishedByTarget(VisibilityConditionRule rule) {
        Bundle owner = FrameworkUtil.getBundle(rule.getClass());
        return owner != null && TARGET.equals(owner.getSymbolicName());
    }


    // ------------------------------------------------------------- bootstrap

    /**
     * Handles the case where this module is installed onto a server whose target is <em>already</em>
     * running: the target's INSTALLED event is long past, so the listener would never fire.
     * <p>
     * Unlike the listener path the target's rules are already registered here, so the uninstall does
     * evict them - the listener repairs that, re-entrantly, because {@code uninstall()} fires
     * UNINSTALLED synchronously.
     */
    static void runBootstrapIfNeeded(BundleContext context) {
        Bundle target = findActiveTarget(context);
        if (target == null) {
            return;
        }
        if (!declaresConditionTypes(target)) {
            return;
        }
        if (findLiveSources(context).isEmpty()) {
            return;
        }
        logger.info("{} v{} is already active and {} is still installed; removing it now.",
                TARGET, target.getVersion(), SOURCES);
        if (uninstallSourcesLocally(context) > 0) {
            // The node types are handed over by the target's own patch script, which runs when it
            // next resolves - and it has already resolved by the time this path is reached.
            logger.warn("The condition node types are still owned by {}. Redeploy or refresh {} to "
                    + "complete the migration.", SOURCES, TARGET);
        }
    }
}
