/*
 * Copyright (c) 2010-2025 Haifeng Li. All rights reserved.
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 */
package smile.manifold;

import java.util.Collection;
import smile.data.SparseDataset;
import smile.graph.AdjacencyList;
import smile.graph.Graph.Edge;
import smile.graph.NearestNeighborGraph;
import smile.math.MathEx;
import smile.math.distance.Distance;
import smile.math.matrix.ARPACK;
import smile.math.matrix.Matrix;
import smile.math.matrix.SparseMatrix;
import smile.util.SparseArray;

/**
 * Laplacian Eigenmaps. Using the notion of the Laplacian of the nearest
 * neighbor adjacency graph, Laplacian Eigenmaps computes a low dimensional
 * representation of the dataset that optimally preserves local neighborhood
 * information in a certain sense. The representation map generated by the
 * algorithm may be viewed as a discrete approximation to a continuous map
 * that naturally arises from the geometry of the manifold.
 * <p>
 * The locality preserving character of the Laplacian Eigenmaps algorithm makes
 * it relatively insensitive to outliers and noise. It is also not prone to
 * "short-circuiting" as only the local distances are used.
 *
 * @see IsoMap
 * @see LLE
 * @see UMAP
 * 
 * <h2>References</h2>
 * <ol>
 * <li> Mikhail Belkin and Partha Niyogi. Laplacian Eigenmaps and Spectral Techniques for Embedding and Clustering. NIPS, 2001. </li>
 * </ol>
 * 
 * @author Haifeng Li
 */
public class LaplacianEigenmap {
    /**
     * Laplacian Eigenmaps with discrete weights.
     * @param data the input data.
     * @param k k-nearest neighbor.
     * @return the embedding coordinates.
     */
    public static double[][] of(double[][] data, int k) {
        return of(data, k, 2, -1);
    }

    /**
     * Laplacian Eigenmaps with Gaussian kernel.
     * @param data the input data.
     * @param d the dimension of the manifold.
     * @param k k-nearest neighbor.
     * @param t the smooth/width parameter of heat kernel exp(-||x-y||<sup>2</sup> / t).
     *          Non-positive value means discrete weights.
     * @return the embedding coordinates.
     */
    public static double[][] of(double[][] data, int k, int d, double t) {
        return of(data, MathEx::distance, k, d, t);
    }

    /**
     * Laplacian Eigenmaps with discrete weights.
     * @param data the input data.
     * @param distance the distance function.
     * @param k k-nearest neighbor.
     * @param <T> the data type of points.
     * @return the embedding coordinates.
     */
    public static <T> double[][] of(T[] data, Distance<T> distance, int k) {
        return of(data, distance, k, 2, -1);
    }

    /**
     * Laplacian Eigenmaps with discrete weights.
     * @param data the input data.
     * @param distance the distance function.
     * @param k k-nearest neighbor.
     * @param <T> the data type of points.
     * @return the embedding coordinates.
     */
    public static <T> double[][] of(T[] data, Distance<T> distance, int k, int d, double t) {
        // Use the largest connected component of nearest neighbor graph.
        NearestNeighborGraph nng = NearestNeighborGraph.of(data, distance, k);
        return of(nng.largest(false), d, t);
    }

    /**
     * Laplacian Eigenmaps with Gaussian kernel.
     * @param nng the k-nearest neighbor graph.
     * @param d the dimension of the manifold.
     * @param t the smooth/width parameter of heat kernel exp(-||x-y||<sup>2</sup> / t).
     *          Non-positive value means discrete weights.
     * @return the embedding coordinates.
     */
    public static double[][] of(NearestNeighborGraph nng, int d, double t) {
        AdjacencyList graph = nng.graph(false);
        int n = graph.getVertexCount();

        double[] D = new double[n];
        double gamma = -1.0 / t;

        SparseArray[] W = new SparseArray[n];
        for (int i = 0; i < n; i++) {
            SparseArray Wi = new SparseArray();
            Collection<Edge> edges = graph.getEdges(i);
            for (Edge edge : edges) {
                double w = t <= 0 ? 1.0 : Math.exp(gamma * edge.weight() * edge.weight());
                Wi.set(edge.v(), w);
                D[i] += w;
            }
            D[i] = 1 / Math.sqrt(D[i]);
            W[i] = Wi;
        }

        for (int i = 0; i < n; i++) {
            double Di = D[i];
            SparseArray Wi = W[i];
            Wi.update((j, value) -> -Di * value * D[j]);
            Wi.set(i, 1.0);
        }

        // Here L is actually I - D^(-1/2) * W * D^(-1/2)
        SparseMatrix L = SparseDataset.of(W, n).toMatrix();

        // ARPACK may not find all needed eigenvalues for k = d + 1.
        // Hack it with 10 * (d + 1).
        Matrix.EVD eigen = ARPACK.syev(L, ARPACK.SymmOption.SM, Math.min(10*(d+1), n-1));

        Matrix V = eigen.Vr;
        double[][] coordinates = new double[n][d];
        for (int j = d; --j >= 0; ) {
            double norm = 0.0;
            int c = V.ncol() - j - 2;
            for (int i = 0; i < n; i++) {
                double xi = V.get(i, c) * D[i];
                coordinates[i][j] = xi;
                norm += xi * xi;
            }

            norm = Math.sqrt(norm);
            for (int i = 0; i < n; i++) {
                coordinates[i][j] /= norm;
            }
        }

        return coordinates;
    }
}
