package com.opensmpsdeck.headless;

import java.util.Arrays;

/** Rectangular Hungarian matching for a matrix of nonnegative pair weights. */
final class MaximumWeightAssignment {
    private MaximumWeightAssignment() {}

    /** Returns the assigned column per row, or -1 when there is no positive match. */
    static int[] match(double[][] weights) {
        int rows = weights.length;
        if (rows == 0) return new int[0];
        int realColumns = weights[0].length;
        // Each row can go unmatched via a zero-weight dummy column.
        int columns = realColumns + rows;
        double[] rowPotential = new double[rows + 1];
        double[] columnPotential = new double[columns + 1];
        int[] rowForColumn = new int[columns + 1];
        int[] previousColumn = new int[columns + 1];

        // Index zero is the sentinel for the row being added.
        for (int row = 1; row <= rows; row++) {
            rowForColumn[0] = row;
            int current = 0;
            double[] distance = new double[columns + 1];
            Arrays.fill(distance, Double.POSITIVE_INFINITY);
            boolean[] visited = new boolean[columns + 1];
            do {
                visited[current] = true;
                int activeRow = rowForColumn[current];
                int next = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int column = 1; column <= columns; column++) {
                    if (visited[column]) continue;
                    double weight = column <= realColumns ? weights[activeRow - 1][column - 1] : 0;
                    double cost = -weight - rowPotential[activeRow] - columnPotential[column];
                    if (cost < distance[column]) {
                        distance[column] = cost;
                        previousColumn[column] = current;
                    }
                    // With equal costs, retain the first column in the caller's stable order.
                    if (distance[column] < delta) {
                        delta = distance[column];
                        next = column;
                    }
                }
                for (int column = 0; column <= columns; column++) {
                    if (visited[column]) {
                        rowPotential[rowForColumn[column]] += delta;
                        columnPotential[column] -= delta;
                    } else {
                        distance[column] -= delta;
                    }
                }
                current = next;
            } while (rowForColumn[current] != 0);

            // Reverse the augmenting path to incorporate this row into the matching.
            while (current != 0) {
                int previous = previousColumn[current];
                rowForColumn[current] = rowForColumn[previous];
                current = previous;
            }
        }

        int[] result = new int[rows];
        Arrays.fill(result, -1);
        for (int column = 1; column <= realColumns; column++) {
            int row = rowForColumn[column] - 1;
            if (row >= 0 && weights[row][column - 1] > 0) result[row] = column - 1;
        }
        return result;
    }
}
