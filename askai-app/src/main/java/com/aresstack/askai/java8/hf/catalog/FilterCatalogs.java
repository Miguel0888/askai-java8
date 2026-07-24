package com.aresstack.askai.java8.hf.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loaded filter catalogs (tasks, libraries, languages, licenses, other), in catalog order.
 * Immutable; built once by {@link CatalogLoader}. Tasks and "other" also expose a grouped view
 * (by category / subgroup) preserving first-seen order, for the tabbed filter UI.
 */
public final class FilterCatalogs {

    private final List<CatalogEntry> tasks;
    private final List<CatalogEntry> libraries;
    private final List<CatalogEntry> languages;
    private final List<CatalogEntry> licenses;
    private final List<CatalogEntry> other;

    public FilterCatalogs(List<CatalogEntry> tasks, List<CatalogEntry> libraries, List<CatalogEntry> languages,
                          List<CatalogEntry> licenses, List<CatalogEntry> other) {
        this.tasks = immutable(tasks);
        this.libraries = immutable(libraries);
        this.languages = immutable(languages);
        this.licenses = immutable(licenses);
        this.other = immutable(other);
    }

    private static List<CatalogEntry> immutable(List<CatalogEntry> values) {
        return values == null
                ? Collections.<CatalogEntry>emptyList()
                : Collections.unmodifiableList(new ArrayList<CatalogEntry>(values));
    }

    public List<CatalogEntry> getTasks() {
        return tasks;
    }

    public List<CatalogEntry> getLibraries() {
        return libraries;
    }

    public List<CatalogEntry> getLanguages() {
        return languages;
    }

    public List<CatalogEntry> getLicenses() {
        return licenses;
    }

    public List<CatalogEntry> getOther() {
        return other;
    }

    /** @return the task entries grouped by category, in first-seen category order. */
    public Map<String, List<CatalogEntry>> getTasksByCategory() {
        return groupBy(tasks);
    }

    /** @return the "other" entries grouped by subgroup, in first-seen subgroup order. */
    public Map<String, List<CatalogEntry>> getOtherBySubgroup() {
        return groupBy(other);
    }

    private static Map<String, List<CatalogEntry>> groupBy(List<CatalogEntry> entries) {
        Map<String, List<CatalogEntry>> grouped = new LinkedHashMap<String, List<CatalogEntry>>();
        for (int i = 0; i < entries.size(); i++) {
            CatalogEntry entry = entries.get(i);
            List<CatalogEntry> bucket = grouped.get(entry.getGroup());
            if (bucket == null) {
                bucket = new ArrayList<CatalogEntry>();
                grouped.put(entry.getGroup(), bucket);
            }
            bucket.add(entry);
        }
        return grouped;
    }
}
