package com.epam.aidial.keycloak.helpers.model;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The user's project entitlement: the ordered, de-duplicated set of project values
 * resolved from the user's Graph groups, each value per the <b>per-group fallback</b>
 * (amended 2026-09-11 — the former list-wide named/degraded "mode" is dissolved):
 * <ul>
 *   <li>a visible {@code displayName} that <b>conforms</b> to the convention regex →
 *       the parsed project id (display name minus the convention prefix);</li>
 *   <li>a visible <b>non-conforming</b> name (e.g. a {@code Project Managers} group) →
 *       <b>excluded</b> — the regex gates every visible name in every case;</li>
 *   <li>no visible name ({@code displayName: null} — no delegated
 *       {@code GroupMember.Read.All}; Graph's documented "limited information"
 *       serialization — the server-side filter still applied) → the group's
 *       <b>object ID</b>. A null-name group is a real membership the Graph filter
 *       admitted and is never dropped.</li>
 * </ul>
 *
 * <p>There is no list-wide mode to configure, store, or flip. A genuinely mixed batch
 * (some names visible, some null) is an anomaly — logged loudly (org reality: groups
 * are all-named or all-null; the log is the observable signal for Graph-filter drift) —
 * and still resolves per group, which is strictly stronger than the former
 * degrade-the-whole-batch rule: a visible non-conforming name can never ride in.
 */
@Slf4j
@Value
public class ProjectEntitlement {

    List<String> values;

    /**
     * Resolves the entitlement from Graph groups.
     *
     * @param groups groups returned by Graph (the server-side prefix filter already applied)
     * @param regex  the project group naming convention
     * @param prefix the convention prefix to strip for the project id
     * @return the entitlement (possibly empty — an empty entitlement yields no claim)
     */
    public static ProjectEntitlement fromGroups(List<ProjectGroup> groups, String regex, String prefix) {
        Pattern convention = Pattern.compile(regex);
        TreeSet<String> values = new TreeSet<>();
        int named = 0;
        int byObjectId = 0;

        for (ProjectGroup group : groups) {
            String name = group.getDisplayName();
            if (name == null && group.getId() == null) {
                // A Graph entry with neither an id nor a name is a serialization
                // anomaly — skip it loudly rather than NPE the brokered login.
                log.warn("Graph returned a group entry with neither id nor displayName — skipped from the entitlement");
                continue;
            }
            if (name == null) {
                // The Graph server-side startswith filter applied even when the name is
                // hidden — the object ID is the group's claim value.
                values.add(group.getId());
                byObjectId++;
            } else if (convention.matcher(name).matches()) {
                values.add(name.startsWith(prefix) ? name.substring(prefix.length()) : name);
                named++;
            } else {
                log.debug("Group name '{}' does not match the project convention — excluded from entitlement", name);
            }
        }

        if (named > 0 && byObjectId > 0) {
            log.warn("Mixed project-group batch: {} groups with visible names, {} without — the entitlement mixes "
                    + "parsed project ids and group object IDs (unexpected; a signal for Graph-filter drift)", named, byObjectId);
        }
        log.debug("Resolved project entitlement per-group: {} named, {} by object ID", named, byObjectId);

        return new ProjectEntitlement(List.copyOf(values));
    }
}
