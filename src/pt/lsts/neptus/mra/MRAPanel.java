/*
 * Copyright (c) 2004-2014 Universidade do Porto - Faculdade de Engenharia
 * Laboratório de Sistemas e Tecnologia Subaquática (LSTS)
 * All rights reserved.
 * Rua Dr. Roberto Frias s/n, sala I203, 4200-465 Porto, Portugal
 *
 * This file is part of Neptus, Command and Control Framework.
 *
 * Commercial Licence Usage
 * Licencees holding valid commercial Neptus licences may use this file
 * in accordance with the commercial licence agreement provided with the
 * Software or, alternatively, in accordance with the terms contained in a
 * written agreement between you and Universidade do Porto. For licensing
 * terms, conditions, and further information contact lsts@fe.up.pt.
 *
 * European Union Public Licence - EUPL v.1.1 Usage
 * Alternatively, this file may be used under the terms of the EUPL,
 * Version 1.1 only (the "Licence"), appearing in the file LICENCE.md
 * included in the packaging of this file. You may not use this work
 * except in compliance with the Licence. Unless required by applicable
 * law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific
 * language governing permissions and limitations at
 * https://www.lsts.pt/neptus/licence.
 *
 * For more information please see <http://lsts.fe.up.pt/neptus>.
 *
 * Author: José Pinto
 * 2007/09/25
 */
package pt.lsts.neptus.mra;

import java.awt.BorderLayout;
import java.awt.Component;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Vector;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;

import net.miginfocom.swing.MigLayout;

import org.jdesktop.swingx.JXStatusBar;

import pt.lsts.imc.IMCMessage;
import pt.lsts.imc.lsf.LsfGenericIterator;
import pt.lsts.neptus.NeptusLog;
import pt.lsts.neptus.gui.InfiniteProgressPanel;
import pt.lsts.neptus.i18n.I18n;
import pt.lsts.neptus.mra.importers.IMraLogGroup;
import pt.lsts.neptus.mra.plots.LogMarkerListener;
import pt.lsts.neptus.mra.replay.LogReplay;
import pt.lsts.neptus.mra.visualizations.MRAVisualization;
import pt.lsts.neptus.plugins.PluginUtils;
import pt.lsts.neptus.plugins.PluginsRepository;
import pt.lsts.neptus.types.coord.LocationType;
import pt.lsts.neptus.types.vehicle.VehicleType;
import pt.lsts.neptus.util.FileUtil;
import pt.lsts.neptus.util.ImageUtils;
import pt.lsts.neptus.util.llf.LogTree;
import pt.lsts.neptus.util.llf.LogUtils;
import pt.lsts.neptus.util.llf.LsfTree;
import pt.lsts.neptus.util.llf.LsfTreeMouseAdapter;
import pt.lsts.neptus.util.llf.chart.MRAChartFactory;
import pt.lsts.neptus.util.llf.replay.LLFMsgReplay;

/**
 * @author ZP
 */
@SuppressWarnings("serial")
public class MRAPanel extends JPanel {

    private LsfTree tree;
    private LogTree logTree;

    private IMraLogGroup source = null;
    private final JXStatusBar statusBar = new JXStatusBar();

    private final LogReplay replay;
    private final LLFMsgReplay replayMsg;

    private final JPanel leftPanel = new JPanel(new MigLayout("ins 0"));
    private final JPanel mainPanel = new JPanel(new MigLayout());
    private final JTabbedPane tabbedPane = new JTabbedPane();

    private final JScrollPane jspMessageTree;
    private final JScrollPane jspLogTree;

    private final LinkedHashMap<String, MRAVisualization> visualizationList = new LinkedHashMap<String, MRAVisualization>();
    private final LinkedHashMap<String, Component> openVisualizationList = new LinkedHashMap<String, Component>();
    private final ArrayList<String> loadingVisualizations = new ArrayList<String>();

    private final ArrayList<LogMarker> logMarkers = new ArrayList<LogMarker>();
    private MRAVisualization shownViz = null;

    InfiniteProgressPanel loader = InfiniteProgressPanel.createInfinitePanelBeans("");

    /**
     * Constructor
     * 
     * @param source
     * @param mra
     */
    public MRAPanel(final IMraLogGroup source, NeptusMRA mra) {
        this.source = source;

        if (new File("conf/tides.txt").canRead() && source.getFile("tides.txt") == null) {
            FileUtil.copyFile("conf/tides.txt", new File(source.getFile("."), "tides.txt").getAbsolutePath());
        }

        // Setup interface
        tree = new LsfTree(source);
        logTree = new LogTree(source, this);

        jspMessageTree = new JScrollPane(tree);
        jspLogTree = new JScrollPane(logTree);

        tabbedPane.addTab(I18n.text("Visualizations"), jspLogTree);
        tabbedPane.addTab(I18n.text("Messages"), jspMessageTree);

        leftPanel.add(tabbedPane, "wrap, w 100%, h 100%");

        setLayout(new BorderLayout(3, 3));
        JSplitPane pane = new JSplitPane();

        VehicleType veh = LogUtils.getVehicle(source);
        Date startDate = LogUtils.getStartDate(source);
        String date = startDate != null ? " | <b>" + I18n.text("Date") + ":</b> " + new SimpleDateFormat("dd/MMM/yyyy").format(startDate) : "";

        statusBar.add(new JLabel("<html><b>" + I18n.text("Log") + ":</b> " + source.name() + date
                + ((veh != null) ? " | <b>" + I18n.text("System") + ":</b> " + veh.getName() : "")));

        pane.setLeftComponent(leftPanel);
        pane.setRightComponent(mainPanel);

        pane.setDividerLocation(250);
        pane.setResizeWeight(0);

        tree.addMouseListener(new LsfTreeMouseAdapter(this));

        Vector<MRAVisualization> visualizations = new Vector<>();
        for (String visName : PluginsRepository.getMraVisualizations().keySet()) {
            try {
                Class<?> vis = PluginsRepository.getMraVisualizations().get(visName);

                MRAVisualization visualization = (MRAVisualization) vis.getDeclaredConstructor(MRAPanel.class)
                        .newInstance(this);
                PluginUtils.loadProperties(visualization, "mra");

                if (visualization.canBeApplied(MRAPanel.this.source))
                    visualizations.add(visualization);
            }
            catch (Exception e1) {
                NeptusLog.pub().error(
                        I18n.text("MRA Visualization not loading properly") + ": " + visName + "  [" + e1.getMessage()
                        + "]");
            }
            catch (Error e2) {
                NeptusLog.pub().error(
                        I18n.text("MRA Visualization not loading properly") + ": " + visName + "  [" + e2.getMessage()
                        + "]");
            }
        }

        visualizations.addAll(MRAChartFactory.getScriptedPlots(this));

        Collections.sort(visualizations, new Comparator<MRAVisualization>() {
            @Override
            public int compare(MRAVisualization o1, MRAVisualization o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });

        // Load PluginVisualizations
        for (MRAVisualization viz : visualizations) {
            try {
                // monitor.setNote(I18n.textf("loading %chartname", viz.getName()));
                loadVisualization(viz, false);
            }
            catch (Exception e1) {
                NeptusLog.pub().error(
                        I18n.text("MRA Visualization not loading properly") + ": " + viz.getName() + "  ["
                                + e1.getMessage() + "]");
            }
            catch (Error e2) {
                NeptusLog.pub().error(
                        I18n.text("MRA Visualization not loading properly") + ": " + viz.getName() + "  ["
                                + e2.getMessage() + "]");
            }
        }

        replay = new LogReplay(this);
        loadVisualization(replay, false);

        replayMsg = new LLFMsgReplay(this);
        loadVisualization(replayMsg, false);

        add(pane, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        for (int i = 0; i < logTree.getRowCount(); i++) {
            logTree.expandRow(i);
        }

        // Load markers
        loadMarkers();

        mra.getMRAMenuBar().setUpExportersMenu(source);
    }

    public void loadVisualization(MRAVisualization vis, boolean open) {
        // Doesnt exist already, load..
        if (!visualizationList.keySet().contains(vis.getName())) {
            ImageIcon icon = vis.getIcon();

            if (icon == null) {
                icon = ImageUtils.getIcon("images/menus/graph.png");
            }
            visualizationList.put(vis.getName(), vis);
            logTree.addVisualization(vis);

            if (open) {
                openVisualization(vis);
            }
        }
        else {
            if (open) {
                openVisualization(vis);
            }
        }
    }

    public void openVisualization(MRAVisualization viz) {
        new Thread(new LoadTask(viz), "Open viz " + viz.getName()).start();
    }

    public void addMarker(LogMarker marker) {

        if (existsMark(marker))
            return;

        // Calculate marker location
        if (marker.lat == 0 && marker.lon == 0) {
            IMCMessage m = source.getLog("EstimatedState").getEntryAtOrAfter(new Double(marker.timestamp).longValue());
            LocationType loc = LogUtils.getLocation(m);

            marker.lat = loc.getLatitudeAsDoubleValueRads();
            marker.lon = loc.getLongitudeAsDoubleValueRads();
        }
        logTree.addMarker(marker);
        logMarkers.add(marker);

        // getTimestampsForMarker(marker, 2);

        for (MRAVisualization vis : visualizationList.values()) {
            if (vis instanceof LogMarkerListener) {
                ((LogMarkerListener) vis).addLogMarker(marker);
            }
        }

        saveMarkers();

    }

    public void removeMarker(LogMarker marker) {
        logTree.removeMarker(marker);
        logMarkers.remove(marker);
        for (MRAVisualization vis : visualizationList.values()) {
            if (vis instanceof LogMarkerListener) {
                try {
                    ((LogMarkerListener) vis).removeLogMarker(marker);
                }
                catch (Exception e) {
                    NeptusLog.pub().error(e);
                }
            }
        }
    }

    public void removeTreeObject(Object obj) {
        logTree.remove(obj);
    }

    public boolean existsMark(LogMarker marker) {
        for (LogMarker m : logMarkers) {
            if (m.label.equals(marker.label))
                return true;
        }
        return false;
    }

    public void getTimestampsForMarker(LogMarker marker, double distance) {
        LsfGenericIterator i = source.getLsfIndex().getIterator("EstimatedState");
        LocationType l = marker.getLocation();

        for (IMCMessage state = i.next(); i.hasNext(); state = i.next()) {
            LocationType loc = new LocationType(Math.toDegrees(state.getDouble("lat")), Math.toDegrees(state
                    .getDouble("lon")));
            loc.translatePosition(state.getDouble("x"), state.getDouble("y"), 0);

            if (loc.getDistanceInMeters(l) <= distance) {
                NeptusLog.pub().info("<###> " + marker.label + " --- " + state.getTimestampMillis());
            }
        }
    }

    public LogReplay getMissionReplay() {
        return replay;
    }

    /**
     * @return the tree
     */
    public LsfTree getTree() {
        return tree;
    }

    /**
     * @return the logTree
     */
    public final LogTree getLogTree() {
        return logTree;
    }

    /**
     * @return the source
     */
    public final IMraLogGroup getSource() {
        return source;
    }

    public ArrayList<LogMarker> getMarkers() {
        return logMarkers;
    }

    public InfiniteProgressPanel getLoader() {
        return loader;
    }

    public void cleanup() {
        NeptusLog.pub().info("MRA Cleanup");
        tree.removeAll();
        tree = null;

        logTree.removeAll();
        logTree = null;

        for (MRAVisualization vis : visualizationList.values()) {
            vis.onCleanup();
            vis = null;
        }

        openVisualizationList.clear();

        saveMarkers();

        source.cleanup();
        source = null;
    }

    public void loadMarkers() {
        logMarkers.clear();
        logMarkers.addAll(LogMarker.load(source));
        Collections.sort(logMarkers);
        for (LogMarker lm : logMarkers)
            logTree.addMarker(lm);
    }

    public void saveMarkers() {
        LogMarker.save(logMarkers, source);
    }

    public void synchVisualizations(LogMarker marker) {
        for (MRAVisualization v : visualizationList.values()) {
            if (v instanceof LogMarkerListener)
                ((LogMarkerListener) v).GotoMarker(marker);
        }
    }

    class LoadTask implements Runnable {
        MRAVisualization vis;

        public LoadTask(MRAVisualization vis) {
            this.vis = vis;
        }

        @Override
        public void run() {
            // Check for existence
            Component c;
            if (openVisualizationList.containsKey(vis.getName())) {
                c = openVisualizationList.get(vis.getName());
            }
            else if (loadingVisualizations.contains(vis.getName())) {
                loader.setText(I18n.textf("Loading %visName", vis.getName()));
                loader.start();
                c = loader;
            }
            else {
                loadingVisualizations.add(vis.getName());

                // Do the loading
                mainPanel.removeAll();
                mainPanel.repaint();
                mainPanel.add(loader, "w 100%, h 100%");

                loader.setText(I18n.textf("Loading %visName", vis.getName()));
                loader.start();

                c = vis.getComponent(source, MRAProperties.defaultTimestep);
                openVisualizationList.put(vis.getName(), c);

                // Add markers
                // For every LogMarker just call the handler of the new visualization
                if (vis instanceof LogMarkerListener) {
                    for (LogMarker marker : logMarkers) {
                        ((LogMarkerListener) vis).addLogMarker(marker);
                    }
                }

                loader.stop();
                loadingVisualizations.remove(vis.getName());
            }

            if (shownViz != null)
                shownViz.onHide();

            shownViz = vis;
            vis.onShow();
            mainPanel.removeAll();
            mainPanel.add(c, "w 100%, h 100%");

            mainPanel.revalidate();
            mainPanel.repaint();
        }
    }
}
