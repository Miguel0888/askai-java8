package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects the repeated RESULT BLOCKS inside the chosen ORGANIC_RESULTS container: descend through
 * single-child wrappers, cluster the sibling blocks by simplified structure signature, take the
 * largest cluster of at least the configured repetition count, then resolve each block's primary
 * title link, snippet and sitelinks. A single anchor is never a result; blocks without a
 * qualifying primary link are rejected with a reason (diagnostics).
 */
public final class SearchResultBlockDetector {

    /** The detected blocks plus the reasons for everything that was rejected. */
    public static final class Detection {
        public final List<DetectedResultBlock> blocks;
        public final List<String> rejectionReasons;

        Detection(List<DetectedResultBlock> blocks, List<String> rejectionReasons) {
            this.blocks = Collections.unmodifiableList(blocks);
            this.rejectionReasons = Collections.unmodifiableList(rejectionReasons);
        }
    }

    private final LegacyBrowserSearchSettings settings;
    private final DomStructureClusterer clusterer;
    private final PrimaryResultLinkResolver primaryResolver;
    private final SnippetExtractor snippetExtractor;

    public SearchResultBlockDetector(LegacyBrowserSearchSettings settings) {
        this.settings = settings;
        this.clusterer = new DomStructureClusterer(
                settings.analysis.resultBlockSimilarityThreshold);
        this.primaryResolver = new PrimaryResultLinkResolver(settings.analysis,
                settings.extraction);
        this.snippetExtractor = new SnippetExtractor(settings.extraction);
    }

    public Detection detect(RenderedPageDocument document, SearchPageLayoutResolution resolution) {
        List<String> reasons = new ArrayList<String>();
        if (!resolution.hasOrganicResultsContainer()
                || !document.snapshotId.equals(resolution.snapshotId)) {
            reasons.add(document.snapshotId.equals(resolution.snapshotId)
                    ? "no organic results container was resolved"
                    : "stale resolution: snapshot " + resolution.snapshotId
                            + " does not match document " + document.snapshotId);
            return new Detection(Collections.<DetectedResultBlock>emptyList(), reasons);
        }
        RenderedContainerDescriptor container =
                document.container(resolution.organicResultsContainerId);
        // Descend through single-child wrappers to the level where the blocks repeat.
        while (container != null && container.childContainerIds.size() == 1) {
            container = document.container(container.childContainerIds.get(0));
        }
        if (container == null || container.childContainerIds.isEmpty()) {
            reasons.add("organic container has no child blocks");
            return new Detection(Collections.<DetectedResultBlock>emptyList(), reasons);
        }

        List<RenderedContainerDescriptor> children =
                new ArrayList<RenderedContainerDescriptor>();
        for (String childId : container.childContainerIds) {
            RenderedContainerDescriptor child = document.container(childId);
            if (child != null && child.visible) {
                children.add(child);
            }
        }
        Collections.sort(children, new Comparator<RenderedContainerDescriptor>() {
            public int compare(RenderedContainerDescriptor a, RenderedContainerDescriptor b) {
                return Integer.compare(a.siblingIndex, b.siblingIndex);
            }
        });

        List<List<RenderedContainerDescriptor>> clusters = clusterer.cluster(children);
        List<RenderedContainerDescriptor> resultCluster = null;
        for (List<RenderedContainerDescriptor> cluster : clusters) {
            if (cluster.size() >= settings.analysis.minimumRepeatedSiblingCount
                    && (resultCluster == null || cluster.size() > resultCluster.size())) {
                resultCluster = cluster;
            }
        }
        if (resultCluster == null) {
            reasons.add("no repeated block structure: largest cluster of "
                    + largest(clusters) + " similar siblings is below the required "
                    + settings.analysis.minimumRepeatedSiblingCount);
            return new Detection(Collections.<DetectedResultBlock>emptyList(), reasons);
        }

        Map<String, List<RenderedLinkDescriptor>> linksByContainer = indexLinks(document);
        Map<String, List<String>> childrenByParent = indexChildren(document);
        List<DetectedResultBlock> blocks = new ArrayList<DetectedResultBlock>();
        int rank = 0;
        for (RenderedContainerDescriptor block : resultCluster) {
            if (blocks.size() >= settings.extraction.maximumExtractedCandidates) {
                reasons.add("candidate limit " + settings.extraction.maximumExtractedCandidates
                        + " reached — remaining blocks dropped");
                break;
            }
            List<RenderedLinkDescriptor> blockLinks =
                    subtreeLinks(block.containerId, childrenByParent, linksByContainer);
            PrimaryResultLinkResolver.Primary primary = primaryResolver.resolve(blockLinks);
            if (primary == null) {
                reasons.add(block.containerId + ": no qualifying primary link");
                continue;
            }
            String snippet = snippetExtractor.extract(block, primary.link);
            List<RenderedLinkDescriptor> siteLinks = siteLinks(blockLinks, primary.link);
            rank++;
            blocks.add(new DetectedResultBlock(block.containerId, rank, primary.link,
                    primary.confidence, primary.link.visibleText.trim(), snippet,
                    primary.link.displayedDomainText, siteLinks,
                    structuralConfidence(resultCluster.size(), snippet)));
        }
        return new Detection(blocks, reasons);
    }

    /**
     * Settings-derived block confidence: repetition strength, the (always present) title link and
     * the snippet, normalized by their weights — a hit WITH explanatory text scores clearly above
     * an isolated link.
     */
    private double structuralConfidence(int clusterSize, String snippet) {
        double repetition = settings.analysis.repeatedBlockWeight
                * Math.min(1.0, (double) clusterSize / settings.analysis.minimumRepeatedSiblingCount);
        double title = settings.analysis.titleLinkWeight;
        double snippetScore = snippet.isEmpty() ? 0 : settings.analysis.snippetPresenceWeight;
        double maximum = settings.analysis.repeatedBlockWeight
                + settings.analysis.titleLinkWeight + settings.analysis.snippetPresenceWeight;
        return maximum <= 0 ? 0
                : Math.max(0, Math.min(1, (repetition + title + snippetScore) / maximum));
    }

    private List<RenderedLinkDescriptor> siteLinks(List<RenderedLinkDescriptor> blockLinks,
                                                   RenderedLinkDescriptor primary) {
        List<RenderedLinkDescriptor> siteLinks = new ArrayList<RenderedLinkDescriptor>();
        for (RenderedLinkDescriptor link : blockLinks) {
            if (siteLinks.size() >= settings.extraction.maximumSiteLinksPerResult) {
                break;
            }
            if (!link.linkId.equals(primary.linkId) && link.visible
                    && !link.resolvedTargetUrl.isEmpty()
                    && link.domainClassification == DomainClassification.EXTERNAL_DOMAIN
                    && !link.resolvedTargetUrl.equals(primary.resolvedTargetUrl)) {
                siteLinks.add(link);
            }
        }
        return siteLinks;
    }

    private static int largest(List<List<RenderedContainerDescriptor>> clusters) {
        int largest = 0;
        for (List<RenderedContainerDescriptor> cluster : clusters) {
            largest = Math.max(largest, cluster.size());
        }
        return largest;
    }

    private static Map<String, List<RenderedLinkDescriptor>> indexLinks(
            RenderedPageDocument document) {
        Map<String, List<RenderedLinkDescriptor>> byContainer =
                new HashMap<String, List<RenderedLinkDescriptor>>();
        for (RenderedLinkDescriptor link : document.links) {
            List<RenderedLinkDescriptor> links = byContainer.get(link.containerId);
            if (links == null) {
                links = new ArrayList<RenderedLinkDescriptor>();
                byContainer.put(link.containerId, links);
            }
            links.add(link);
        }
        return byContainer;
    }

    private static Map<String, List<String>> indexChildren(RenderedPageDocument document) {
        Map<String, List<String>> byParent = new HashMap<String, List<String>>();
        for (RenderedContainerDescriptor container : document.containers) {
            byParent.put(container.containerId, container.childContainerIds);
        }
        return byParent;
    }

    /** All links of the block's container SUBTREE, in document order. */
    private static List<RenderedLinkDescriptor> subtreeLinks(
            String blockId, Map<String, List<String>> childrenByParent,
            Map<String, List<RenderedLinkDescriptor>> linksByContainer) {
        List<RenderedLinkDescriptor> links = new ArrayList<RenderedLinkDescriptor>();
        Set<String> visited = new HashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(blockId);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            List<RenderedLinkDescriptor> own = linksByContainer.get(id);
            if (own != null) {
                links.addAll(own);
            }
            List<String> children = childrenByParent.get(id);
            if (children != null) {
                queue.addAll(children);
            }
        }
        Collections.sort(links, new Comparator<RenderedLinkDescriptor>() {
            public int compare(RenderedLinkDescriptor a, RenderedLinkDescriptor b) {
                return a.linkId.compareTo(b.linkId);
            }
        });
        return links;
    }
}
