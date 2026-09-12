package com.epam.aidial.keycloak.helpers.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The per-group fallback contract (amended 2026-09-11 — the list-wide
 * named/degraded "mode" is dissolved): a conforming visible name → parsed
 * project id; a non-conforming visible name → excluded (in EVERY batch shape);
 * a null name → the group's object ID. Regex-gated in every case; no list-wide
 * mode to configure, store, or flip.
 */
public class ProjectEntitlementTest {

    private static final String REGEX = "^Project [A-Za-z0-9]+-[A-Za-z0-9]+$";
    private static final String PREFIX = "Project ";

    @Test
    public void allNamedBatchYieldsRegexGatedParsedIds() {
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", "Project EPM-AEM"),
                new ProjectGroup("id-2", "Project ABC-42"),
                new ProjectGroup("id-3", "Project Templates"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("ABC-42", "EPM-AEM"), entitlement.getValues());
    }

    @Test
    public void allNullBatchYieldsObjectIds() {
        List<ProjectGroup> groups = List.of(new ProjectGroup("id-1", null), new ProjectGroup("id-2", null));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("id-1", "id-2"), entitlement.getValues());
    }

    @Test
    public void mixedBatchResolvesPerGroupNotPerBatch() {
        // The 2026-09-11 ruling: per-group fallback — names where visible,
        // object IDs where not; nothing list-wide degrades.
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", null),
                new ProjectGroup("id-2", "Project EPM-AEM"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("EPM-AEM", "id-1"), entitlement.getValues());
    }

    @Test
    public void nonConformingVisibleNameExcludedInNamedBatch() {
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", "Project Managers"),
                new ProjectGroup("id-2", "Project EPM-AEM"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("EPM-AEM"), entitlement.getValues());
    }

    @Test
    public void nonConformingVisibleNameExcludedInMixedBatch() {
        // The strict pin over the former behavior: in a mixed batch the old
        // degrade-the-whole-batch rule let a visible non-conforming name ride
        // in as a raw object ID, regex never applied. The per-group fallback
        // regex-gates every visible name in every batch shape.
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", null),
                new ProjectGroup("id-2", "Project Managers"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("id-1"), entitlement.getValues());
    }

    @Test
    public void entryWithNeitherIdNorNameIsSkipped() {
        // A Graph serialization anomaly (no id, no name) must not NPE the
        // brokered login — the entry is skipped loudly, the rest resolves.
        List<ProjectGroup> groups = List.of(
                new ProjectGroup(null, null),
                new ProjectGroup("id-2", "Project EPM-AEM"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("EPM-AEM"), entitlement.getValues());
    }

    @Test
    public void valuesAreSortedAndDeduplicated() {
        List<ProjectGroup> groups = List.of(
                new ProjectGroup("id-1", "Project B-2"),
                new ProjectGroup("id-2", "Project A-1"),
                new ProjectGroup("id-3", "Project A-1"));

        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(groups, REGEX, PREFIX);

        assertEquals(List.of("A-1", "B-2"), entitlement.getValues());
    }

    @Test
    public void emptyGroupListYieldsEmptyEntitlement() {
        ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(List.of(), REGEX, PREFIX);

        assertEquals(List.of(), entitlement.getValues());
    }
}
