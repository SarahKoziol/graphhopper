/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper GmbH licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.reader.gtfs;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.TimeDependentRoutingAlgorithm;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.weighting.TimeDependentWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.SPTEntry;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;

import java.util.*;

/**
 * Profile query Dijstra impl for lexico first problems. Among all earliest
 * arrivals it returns the one with least transfers for all profile results.
 *
 */
class ProfileDijkstra implements TimeDependentRoutingAlgorithm {
    private final Graph graph;
    private final PtFlagEncoder flagEncoder;
    private final Weighting weighting;
    private final SetMultimap<Integer, SPTEntry> fromMap;
    private final PriorityQueue<SPTEntry> fromHeap;
    private final int maxVisitedNodes;
    private GtfsStorage gtfsStorage;
    private int visitedNodes;

    ProfileDijkstra(Graph graph, Weighting weighting, int maxVisitedNodes, GtfsStorage gtfsStorage) {
        this.graph = graph;
        this.weighting = weighting;
        this.flagEncoder = (PtFlagEncoder) weighting.getFlagEncoder();
        this.maxVisitedNodes = maxVisitedNodes;
        this.gtfsStorage = gtfsStorage;
        int size = Math.min(Math.max(200, graph.getNodes() / 10), 2000);
        fromHeap = new PriorityQueue<>(
                size/* , new SPTEntryComparator(gtfsStorage) */);
        fromMap = HashMultimap.create();
    }

    @Override
    public Path calcPath(int from, int to, int earliestDepartureTime) {
        throw new UnsupportedOperationException();
    }

    List<Path> calcPaths(int from, Set<Integer> to, int startTime) {
        Set<SPTEntry> targetLabels = new HashSet<>();
        Set<SPTEntry> foundSolutions = new HashSet<>();
        SPTEntry label = new SPTEntry(EdgeIterator.NO_EDGE, from, startTime, 0);
        fromMap.put(from, label);
        if (to.contains(from)) {
            targetLabels.add(label);
        }
        EdgeExplorer explorer = graph.createEdgeExplorer(new DefaultEdgeFilter(flagEncoder, false, true));
        while (true) {
            visitedNodes++;
            if (maxVisitedNodes < getVisitedNodes())
                break;

            if (foundSolutions.size() >= 3)
                break;

            int startNode = label.adjNode;
            EdgeIterator iter = explorer.setBaseNode(startNode);
            boolean foundEnteredTimeExpandedNetworkEdge = false;
            while (iter.next()) {
                if (iter.getEdge() == label.edge)
                    continue;

                GtfsStorage.EdgeType edgeType = flagEncoder.getEdgeType(iter.getFlags());
                if (edgeType == GtfsStorage.EdgeType.BOARD_EDGE) {
                    int trafficDay = (int) (label.weight) / (24 * 60 * 60);
                    if (!((BoardEdge) gtfsStorage.getEdges().get(iter.getEdge())).validOn.get(trafficDay)) {
                        continue;
                    }
                } else if (edgeType == GtfsStorage.EdgeType.ENTER_TIME_EXPANDED_NETWORK) {
                    if ((int) (label.weight) % (24 * 60 * 60) > flagEncoder.getTime(iter.getFlags())) {
                        continue;
                    } else {
                        if (foundEnteredTimeExpandedNetworkEdge) {
                            continue;
                        } else {
                            foundEnteredTimeExpandedNetworkEdge = true;
                        }
                    }
                } else if (edgeType == GtfsStorage.EdgeType.STOP_EXIT_NODE_MARKER_EDGE
                        || edgeType == GtfsStorage.EdgeType.STOP_NODE_MARKER_EDGE) {
                    continue;
                }

                double tmpWeight;
                int tmpNTransfers = label.nTransfers;
                if (weighting instanceof TimeDependentWeighting) {
                    tmpWeight = ((TimeDependentWeighting) weighting).calcWeight(iter, false, label.edge, label.weight)
                            + label.weight;
                    tmpNTransfers += ((TimeDependentWeighting) weighting).calcNTransfers(iter);
                } else {
                    tmpWeight = weighting.calcWeight(iter, false, label.edge) + label.weight;
                }
                if (Double.isInfinite(tmpWeight))
                    continue;

                Set<SPTEntry> sptEntries = fromMap.get(iter.getAdjNode());
                SPTEntry nEdge = new SPTEntry(iter.getEdge(), iter.getAdjNode(), tmpWeight, tmpNTransfers);
                nEdge.parent = label;
                if (improves(nEdge, sptEntries) && improves(nEdge, targetLabels)) {
                    removeDominated(nEdge, sptEntries);
                    if (to.contains(iter.getAdjNode())) {
                        addToFoundSolutions(nEdge, foundSolutions);
                        // no need to relax other edges. node at destination
                        break;
                    }
                    fromMap.put(iter.getAdjNode(), nEdge);
                    if (to.contains(iter.getAdjNode())) {
                        targetLabels.add(nEdge);
                    }
                    fromHeap.add(nEdge);
                }
            }

            if (fromHeap.isEmpty())
                break;

            label = fromHeap.poll();
            if (label == null)
                throw new AssertionError("Empty edge cannot happen");
        }
        List<Path> result = new ArrayList<>();
        for (SPTEntry solution : foundSolutions) {
            result.add(new Path(graph, weighting).setWeight(solution.weight).setSPTEntry(solution).extract());
        }
        return result;
    }

    private void addToFoundSolutions(SPTEntry label, Set<SPTEntry> foundSolutions) {
        // TODO keep only non-dominated routes and use the departure time as
        // criteria. Now the set of found solutions could contain routes that
        // make no sense. Once the actual departure attribute is available, this
        // set can be easily filtered
        foundSolutions.add(label);
    }

    private boolean improves(SPTEntry me, Set<SPTEntry> sptEntries) {
        for (SPTEntry they : sptEntries) {
            if (they.weight < me.weight || (they.weight == me.weight && they.nTransfers < me.nTransfers)) {
                return false;
            }
        }
        return true;
    }

    private void removeDominated(SPTEntry me, Set<SPTEntry> sptEntries) {
        for (Iterator<SPTEntry> iterator = sptEntries.iterator(); iterator.hasNext();) {
            SPTEntry sptEntry = iterator.next();
            if (dominates(me, sptEntry)) {
                fromHeap.remove(sptEntry);
                iterator.remove();
            }
        }
    }

    @Override
    public List<Path> calcPaths(int from, int to, int earliestDepartureTime) {
        throw new UnsupportedOperationException();
    }

    /**
     * Lexico first weight, transfers
     * 
     * @param me
     * @param they
     * @return
     */
    private boolean dominates(SPTEntry me, SPTEntry they) {
        if (me.weight < they.weight) {
            return true;
        }

        if (me.weight == they.weight && me.nTransfers < they.nTransfers) {
            return true;
        }
        return false;
    }

    @Override
    public Path calcPath(int from, int to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Path> calcPaths(int from, int to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setMaxVisitedNodes(int numberOfNodes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getVisitedNodes() {
        return visitedNodes;
    }

    @Override
    public String getName() {
        return "Ulrich";
    }

}
