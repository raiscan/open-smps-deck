package com.opensmps.deck.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Breadcrumb navigation bar showing the current path in the hierarchy:
 * e.g. "FM1 Chain > Verse A > Bass Riff". Click any segment to navigate back.
 */
public class BreadcrumbBar extends HBox {

    public record Crumb(String label, int depth) {}

    private final List<Crumb> crumbs = new ArrayList<>();
    private IntConsumer onNavigate; // callback: depth to navigate to

    public BreadcrumbBar() {
        setSpacing(0);
        setPadding(new Insets(2, 4, 2, 4));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #1e1e2e;");
        setPrefHeight(24);
        setMinHeight(24);
    }

    public void setOnNavigate(IntConsumer callback) {
        this.onNavigate = callback;
    }

    /** Replace the breadcrumb trail with a new set of crumbs. */
    public void setCrumbs(List<Crumb> newCrumbs) {
        crumbs.clear();
        crumbs.addAll(newCrumbs);
        rebuild();
    }

    /** Push a new crumb at the given depth. */
    public void push(String label, int depth) {
        // Remove anything at or beyond this depth
        crumbs.removeIf(c -> c.depth() >= depth);
        crumbs.add(new Crumb(label, depth));
        rebuild();
    }

    /** Navigate up one level (remove the last crumb). */
    public boolean navigateUp() {
        if (crumbs.size() <= 1) return false;
        crumbs.removeLast();
        rebuild();
        if (onNavigate != null && !crumbs.isEmpty()) {
            onNavigate.accept(crumbs.getLast().depth());
        }
        return true;
    }

    public int getCurrentDepth() {
        return crumbs.isEmpty() ? 0 : crumbs.getLast().depth();
    }

    private void rebuild() {
        getChildren().clear();

        for (int i = 0; i < crumbs.size(); i++) {
            Crumb crumb = crumbs.get(i);
            boolean isLast = i == crumbs.size() - 1;

            if (i > 0) {
                Label separator = new Label(" > ");
                separator.setStyle("-fx-text-fill: #666688; -fx-font-family: 'Monospaced'; -fx-font-size: 11;");
                getChildren().add(separator);
            }

            Label label = new Label(crumb.label());
            if (isLast) {
                label.setStyle("-fx-text-fill: #ccddee; -fx-font-family: 'Monospaced'; " +
                    "-fx-font-size: 11; -fx-font-weight: bold;");
            } else {
                label.setStyle("-fx-text-fill: #88aacc; -fx-font-family: 'Monospaced'; " +
                    "-fx-font-size: 11; -fx-cursor: hand;");
                final int targetDepth = crumb.depth();
                label.setOnMouseClicked(e -> {
                    // Remove crumbs deeper than the clicked one
                    crumbs.removeIf(c -> c.depth() > targetDepth);
                    rebuild();
                    if (onNavigate != null) {
                        onNavigate.accept(targetDepth);
                    }
                });
            }
            getChildren().add(label);
        }
    }
}
