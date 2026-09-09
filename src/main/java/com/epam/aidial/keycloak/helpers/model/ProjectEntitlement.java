package com.epam.aidial.keycloak.helpers.model;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The user's project entitlement: the ordered, de-duplicated set of convention-matching
 * project values resolved from the user's Graph groups, plus the mode it was resolved in.
 *
 * <p>Mode semantics (D-019, config-repo spec 02 §5):
 * <ul>
 *   <li><b>named</b> — every group's {@code displayName} was readable; values are parsed
 *       project ids (display name minus the convention prefix), convention-regex filtered.</li>
 *   <li><b>degraded</b> — at least one {@code displayName} was {@code null} (no delegated
 *       {@code GroupMember.Read.All}); values are the groups' object IDs. Fail conservative:
 *       a mixed batch degrades entirely and is logged.</li>
 * </ul>
 *
 * <p>Only convention-conforming groups ever yield a value (D-019's fail-loud intent):
 * the Graph server-side {@code startswith(displayName,'Project ')} filter pre-selects the
 * candidate set, and the convention regex filters named mode further. Non-conforming names
 * are logged at debug level and never emitted.
 */
@Slf4j
@Value
public class ProjectEntitlement {

    public static final String MODE_NAMED = "named";
    public static final String MODE_DEGRADED = "degraded";

    List<String> values;

    String mode;

    /**
     * Resolves the entitlement from Graph groups.
     *
     * @param groups groups returned by Graph (the server-side prefix filter already applied)
     * @param regex  the project group naming convention
     * @param prefix the convention prefix to strip for the project id (named mode)
     * @return the entitlement (possibly empty — an empty entitlement yields no claim)
     */
    public static ProjectEntitlement fromGroups(List<ProjectGroup> groups, String regex, String prefix) {
        TreeSet<String> values = new TreeSet<>();
        String mode;

        boolean degraded = groups.stream().anyMatch(g -> g.getDisplayName() == null);
        if (degraded) {
            mode = MODE_DEGRADED;
            // Server-side startswith filter was the only convention gate —
            // group object IDs are the claim values (fail conservative).
            groups.forEach(g -> values.add(g.getId()));
            log.debug("Degraded mode ({} of {} groups without readable displayName) — entitlement = group object IDs",
                    groups.stream().filter(g -> g.getDisplayName() == null).count(), groups.size());
        } else {
            mode = MODE_NAMED;
            Pattern convention = Pattern.compile(regex);
            for (ProjectGroup group : groups) {
                String name = group.getDisplayName();
                if (!convention.matcher(name).matches()) {
                    log.debug("Group name '{}' does not match the project convention — excluded from entitlement", name);
                    continue;
                }
                values.add(name.startsWith(prefix) ? name.substring(prefix.length()) : name);
            }
        }

        return new ProjectEntitlement(List.copyOf(values), mode);
    }
}
