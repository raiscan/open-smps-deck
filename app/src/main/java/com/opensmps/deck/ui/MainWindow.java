package com.opensmps.deck.ui;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.model.Song;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * Main application window with tab-based multi-document layout.
 *
 * <p>Layout: MenuBar + TransportBar (top), TabPane (center).
 * Each tab wraps a {@link SongTab} with its own TrackerGrid, OrderListPanel,
 * and InstrumentPanel.
 */
public class MainWindow {

    private final Stage stage;
    private final BorderPane root;
    private final PlaybackEngine playbackEngine;
    private final MainWindowFileActions fileActions;
    private final SongTabCoordinator songTabCoordinator;
    private final MainWindowTabLifecycleCoordinator tabLifecycleCoordinator;
    private final TabPane tabPane = new TabPane();
    private TransportBar transportBar;

    public MainWindow(Stage stage) {
        this.stage = stage;
        this.playbackEngine = new PlaybackEngine();
        this.root = new BorderPane();
        this.fileActions = new MainWindowFileActions(
                stage,
                this::getActiveSongTab,
                this::addNewTab,
                this::refreshActiveTabTitle,
                this::showError
        );
        this.songTabCoordinator = new SongTabCoordinator(new SongTabCoordinator.PlaybackGateway() {
            @Override
            public boolean isPlaying() {
                return playbackEngine.isPlaying();
            }

            @Override
            public void stop() {
                playbackEngine.stop();
            }

            @Override
            public void loadSong(Song song) {
                playbackEngine.loadSong(song);
            }

            @Override
            public void play() {
                playbackEngine.play();
            }

            @Override
            public void reload(Song song) {
                playbackEngine.reload(song);
            }

            @Override
            public void playFromPosition(Song song, int orderIndex, int rowIndex) {
                playbackEngine.playFromPosition(song, orderIndex, rowIndex);
            }

            @Override
            public PlaybackEngine.PlaybackPosition getPlaybackPosition() {
                return playbackEngine.getPlaybackPosition();
            }

            @Override
            public void setChannelMute(int channel, boolean muted) {
                playbackEngine.setChannelMute(channel, muted);
            }
        });
        this.tabLifecycleCoordinator = new MainWindowTabLifecycleCoordinator();

        // Wire playback cursor updates
        songTabCoordinator.setPlaybackCursorListener(new SongTabCoordinator.PlaybackCursorListener() {
            @Override
            public void onPlaybackCursorMoved(int orderRow, int patternRow) {
                SongTab tab = getActiveSongTab();
                if (tab != null) {
                    tab.getTrackerGrid().setPlaybackRow(patternRow);
                    tab.getTrackerGrid().setPlaybackOrderRow(orderRow);
                }
            }
            @Override
            public void onPlaybackCursorCleared() {
                SongTab tab = getActiveSongTab();
                if (tab != null) {
                    tab.getTrackerGrid().clearPlaybackCursor();
                }
            }
        });

        songTabCoordinator.setMuteStateProvider(ch -> {
            SongTab tab = getActiveSongTab();
            return tab != null && tab.getTrackerGrid().isChannelMuted(ch);
        });

        javafx.animation.Timeline cursorPollTimer = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(67), // ~15 Hz
                e -> {
                    songTabCoordinator.updatePlaybackCursor();
                    SongTab tab = getActiveSongTab();
                    if (tab != null) {
                        tab.getTrackerGrid().setPlaybackRowsByChannel(playbackEngine.getChannelPlaybackRows());
                    }
                }
            )
        );
        cursorPollTimer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        cursorPollTimer.play();

        setupLayout();
        setupStage();
    }

    private SongTab getActiveSongTab() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getUserData() instanceof SongTab st) {
            return st;
        }
        return null;
    }

    private Tab createSongTabUI(SongTab songTab) {
        songTab.setOnEdited(() -> songTabCoordinator.onSongEdited(songTab.getSong()));
        songTab.buildContent();
        songTab.getTrackerGrid().setPlaybackEngine(playbackEngine);
        songTab.getInstrumentPanel().setPlaybackEngine(playbackEngine);
        songTab.getInstrumentPanel().setOnImportBank(fileActions::onImportVoiceBank);

        // Wire transport callbacks for keyboard shortcuts in TrackerGrid
        songTab.getTrackerGrid().setOnTogglePlayback(() -> songTabCoordinator.onTogglePlayback(songTab.getSong()));
        songTab.getTrackerGrid().setOnStopPlayback(songTabCoordinator::onStopPlayback);
        songTab.getTrackerGrid().setOnPlayFromCursor(() -> {
            int orderIndex = songTab.getOrderListPanel().getSelectedRow();
            int rowIndex = songTab.getTrackerGrid().getCursorRow();
            songTabCoordinator.onPlayFromCursor(songTab.getSong(), orderIndex, rowIndex);
        });

        BorderPane content = new BorderPane();

        if (songTab.isHierarchical()) {
            // Hierarchical layout: SongView (left), BreadcrumbBar+ChainStrip+TrackerGrid (center)
            VBox centerTop = new VBox(songTab.getBreadcrumbBar(), songTab.getChainStrip());
            BorderPane centerPane = new BorderPane();
            centerPane.setTop(centerTop);
            centerPane.setCenter(songTab.getTrackerGrid());

            content.setLeft(songTab.getSongView());
            content.setCenter(centerPane);
            content.setRight(songTab.getInstrumentPanel());
        } else {
            // Legacy layout: TrackerGrid (center), OrderList (bottom)
            content.setCenter(songTab.getTrackerGrid());
            content.setBottom(songTab.getOrderListPanel());
            content.setRight(songTab.getInstrumentPanel());
        }

        songTab.getOrderListPanel().setOnOrderRowSelected(rowIndex -> {
            Song song = songTab.getSong();
            int patternIndex = songTabCoordinator.resolvePatternForOrderSelection(song, rowIndex);
            if (patternIndex >= 0) {
                songTab.getTrackerGrid().setCurrentPatternIndex(patternIndex);
            }
        });

        Tab tab = new Tab(songTab.getTitle(), content);
        tab.setUserData(songTab);
        tab.setClosable(true);
        songTab.setOnDirtyChanged(() -> {
            tab.setText(songTab.getTitle());
            if (tab == tabPane.getSelectionModel().getSelectedItem()) {
                updateTitle();
            }
        });
        return tab;
    }

    private void addNewTab(SongTab songTab) {
        Tab tab = createSongTabUI(songTab);
        // Insert before the [+] button tab (last tab)
        int insertIndex = Math.max(0, tabPane.getTabs().size() - 1);
        tabPane.getTabs().add(insertIndex, tab);
        tabPane.getSelectionModel().select(tab);
    }

    private void setupLayout() {
        MenuBar menuBar = createMenuBar();
        // Placeholder song; replaced by first tab's song immediately below
        transportBar = new TransportBar(playbackEngine, new Song());
        transportBar.setOnSongChanged(this::markActiveSongDirty);
        VBox topContainer = new VBox(menuBar, transportBar);
        root.setTop(topContainer);

        // TabPane with closable tabs
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        // Tab selection listener: update transport and title
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            SongTab activeTab = getActiveSongTab();
            String selectedText = newTab != null ? newTab.getText() : null;
            Object selectedUserData = newTab != null ? newTab.getUserData() : null;
            tabLifecycleCoordinator.onTabSelectionChanged(
                    selectedText,
                    selectedUserData,
                    activeTab,
                    new MainWindowTabLifecycleCoordinator.Callbacks() {
                        @Override
                        public void createNewTab() {
                            Platform.runLater(() -> addNewTab(new SongTab()));
                        }

                        @Override
                        public void activateSong(Song song) {
                            transportBar.setSong(song);
                        }

                        @Override
                        public void updateTitle() {
                            MainWindow.this.updateTitle();
                        }
                    }
            );
        });

        // Initial song tab
        addNewTab(new SongTab());

        // Wire transport to the first tab's song (instead of relying on listener)
        SongTab firstTab = getActiveSongTab();
        if (firstTab != null) {
            transportBar.setSong(firstTab.getSong());
        }

        // [+] button tab (not closable)
        Tab plusTab = new Tab("+");
        plusTab.setClosable(false);
        tabPane.getTabs().add(plusTab);

        root.setCenter(tabPane);
    }

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");

        MenuItem newItem = new MenuItem("New");
        newItem.setAccelerator(KeyCombination.keyCombination("Ctrl+N"));
        newItem.setOnAction(e -> onNew());

        MenuItem openItem = new MenuItem("Open...");
        openItem.setAccelerator(KeyCombination.keyCombination("Ctrl+O"));
        openItem.setOnAction(e -> fileActions.onOpen());

        MenuItem saveItem = new MenuItem("Save");
        saveItem.setAccelerator(KeyCombination.keyCombination("Ctrl+S"));
        saveItem.setOnAction(e -> fileActions.onSave());

        MenuItem saveAsItem = new MenuItem("Save As...");
        saveAsItem.setAccelerator(KeyCombination.keyCombination("Ctrl+Shift+S"));
        saveAsItem.setOnAction(e -> fileActions.onSaveAs());

        SeparatorMenuItem separator = new SeparatorMenuItem();

        MenuItem exportItem = new MenuItem("Export SMPS...");
        exportItem.setOnAction(e -> fileActions.onExportSmps());

        MenuItem exportWavItem = new MenuItem("Export WAV...");
        exportWavItem.setOnAction(e -> fileActions.onExportWav());

        MenuItem importVoicesItem = new MenuItem("Import Voices...");
        importVoicesItem.setOnAction(e -> fileActions.onImportVoices());

        MenuItem importSmpsItem = new MenuItem("Import SMPS...");
        importSmpsItem.setOnAction(e -> fileActions.onImportSmps());

        MenuItem importVoiceBankItem = new MenuItem("Import Voice Bank...");
        importVoiceBankItem.setOnAction(e -> fileActions.onImportVoiceBank());

        MenuItem exportVoiceBankItem = new MenuItem("Export Voice Bank...");
        exportVoiceBankItem.setOnAction(e -> fileActions.onExportVoiceBank());

        fileMenu.getItems().addAll(newItem, openItem, new SeparatorMenuItem(),
                saveItem, saveAsItem, separator, exportItem, exportWavItem,
                new SeparatorMenuItem(), importVoicesItem, importSmpsItem,
                new SeparatorMenuItem(), importVoiceBankItem, exportVoiceBankItem);

        menuBar.getMenus().add(fileMenu);
        return menuBar;
    }

    private void onNew() {
        addNewTab(new SongTab());
    }

    private void updateTitle() {
        SongTab tab = getActiveSongTab();
        String filename = "Untitled";
        if (tab != null) {
            filename = tab.getTitle();
        }
        stage.setTitle("OpenSMPS Deck - " + filename);
    }

    private void refreshActiveTabTitle() {
        SongTab tab = getActiveSongTab();
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (tab != null && selectedTab != null && selectedTab.getUserData() instanceof SongTab) {
            selectedTab.setText(tab.getTitle());
        }
        updateTitle();
    }

    private void markActiveSongDirty() {
        SongTab tab = getActiveSongTab();
        if (tab == null) return;
        tab.setDirty(true);
    }

    private void showError(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void setupStage() {
        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/style.css") != null
                ? getClass().getResource("/style.css").toExternalForm()
                : "");
        stage.setScene(scene);
        updateTitle();
        stage.setMinWidth(800);
        stage.setMinHeight(600);
    }

    public void show() {
        stage.show();
    }

    public Song getCurrentSong() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getSong() : null;
    }

    public PlaybackEngine getPlaybackEngine() {
        return playbackEngine;
    }

    public Stage getStage() {
        return stage;
    }

    public BorderPane getRoot() {
        return root;
    }

    public TrackerGrid getTrackerGrid() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getTrackerGrid() : null;
    }

    public OrderListPanel getOrderListPanel() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getOrderListPanel() : null;
    }

    public InstrumentPanel getInstrumentPanel() {
        SongTab tab = getActiveSongTab();
        return tab != null ? tab.getInstrumentPanel() : null;
    }
}
