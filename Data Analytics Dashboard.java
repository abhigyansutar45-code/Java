import os, textwrap, zipfile, pathlib, json
root = pathlib.Path("/mnt/data/DataWiseAnalytics")
files = {}
files["pom.xml"] = r'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.datawise</groupId><artifactId>datawise-analytics</artifactId><version>1.0.0</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <javafx.version>21.0.6</javafx.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId><version>${javafx.version}</version></dependency>
    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.13.0</version></dependency>
    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-math3</artifactId><version>3.6.1</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration><release>21</release></configuration></plugin>
    <plugin><groupId>org.openjfx</groupId><artifactId>javafx-maven-plugin</artifactId><version>0.0.8</version><configuration><mainClass>com.datawise.MainApp</mainClass></configuration></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version><configuration><useModulePath>false</useModulePath></configuration></plugin>
  </plugins></build>
</project>
'''
files["src/main/java/com/datawise/model/DataSet.java"] = r'''package com.datawise.model;

import java.util.*;

public class DataSet {
    private final List<String> columns;
    private final List<List<String>> rows;
    private final String name;

    public DataSet(String name, List<String> columns, List<List<String>> rows) {
        this.name = Objects.requireNonNullElse(name, "dataset.csv");
        this.columns = new ArrayList<>(columns);
        this.rows = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> normalized = new ArrayList<>(row);
            while (normalized.size() < this.columns.size()) normalized.add("");
            if (normalized.size() > this.columns.size())
                normalized = new ArrayList<>(normalized.subList(0, this.columns.size()));
            this.rows.add(normalized);
        }
    }
    public String getName() { return name; }
    public List<String> getColumns() { return Collections.unmodifiableList(columns); }
    public List<List<String>> getRows() {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> row : rows) copy.add(new ArrayList<>(row));
        return copy;
    }
    public int rowCount() { return rows.size(); }
    public int columnCount() { return columns.size(); }
    public int missingCount() {
        int count = 0;
        for (List<String> row : rows) for (String v : row) if (isMissing(v)) count++;
        return count;
    }
    public int duplicateCount() {
        Set<List<String>> seen = new HashSet<>();
        int count = 0;
        for (List<String> row : rows) if (!seen.add(row)) count++;
        return count;
    }
    public int numericColumnCount() {
        int n = 0;
        for (int c = 0; c < columns.size(); c++) if (isNumericColumn(c)) n++;
        return n;
    }
    public boolean isNumericColumn(int c) {
        boolean found = false;
        for (List<String> row : rows) {
            String v = row.get(c);
            if (isMissing(v)) continue;
            found = true;
            try { Double.parseDouble(v.trim()); }
            catch (NumberFormatException ex) { return false; }
        }
        return found;
    }
    public List<Double> numericValues(int c) {
        List<Double> out = new ArrayList<>();
        for (List<String> row : rows) {
            try { if (!isMissing(row.get(c))) out.add(Double.parseDouble(row.get(c).trim())); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }
    public static boolean isMissing(String value) {
        return value == null || value.trim().isEmpty() || value.trim().equalsIgnoreCase("null")
                || value.trim().equalsIgnoreCase("na") || value.trim().equalsIgnoreCase("n/a");
    }
    public void removeDuplicates() {
        Set<List<String>> seen = new LinkedHashSet<>();
        for (List<String> row : rows) seen.add(new ArrayList<>(row));
        rows.clear();
        for (List<String> row : seen) rows.add(new ArrayList<>(row));
    }
    public void fillMissingMean(int c) {
        List<Double> values = numericValues(c);
        if (values.isEmpty()) return;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        for (List<String> row : rows) if (isMissing(row.get(c))) row.set(c, Double.toString(mean));
    }
    public void fillMissingCategory(int c, String replacement) {
        for (List<String> row : rows) if (isMissing(row.get(c))) row.set(c, replacement);
    }
    public void renameColumn(int c, String newName) {
        if (newName != null && !newName.isBlank()) columns.set(c, newName.trim());
    }
    public void removeColumn(int c) {
        columns.remove(c);
        for (List<String> row : rows) row.remove(c);
    }
    public String valueAt(int row, int col) { return rows.get(row).get(col); }
}
'''
files["src/main/java/com/datawise/service/CsvService.java"] = r'''package com.datawise.service;

import com.datawise.model.DataSet;
import org.apache.commons.csv.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class CsvService {
    public DataSet read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).build().parse(reader)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) throw new IOException("The selected file is empty.");
            List<String> headers = new ArrayList<>();
            for (String h : records.get(0)) headers.add(h == null ? "" : h.trim());
            if (headers.stream().allMatch(String::isBlank)) throw new IOException("The first row has no column headers.");
            List<List<String>> rows = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                CSVRecord record = records.get(i);
                List<String> row = new ArrayList<>();
                for (int c = 0; c < headers.size(); c++) row.add(c < record.size() ? record.get(c) : "");
                rows.add(row);
            }
            return new DataSet(path.getFileName().toString(), headers, rows);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Could not parse this CSV file: " + ex.getMessage(), ex);
        }
    }
    public void write(DataSet data, Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord(data.getColumns());
            for (List<String> row : data.getRows()) printer.printRecord(row);
        }
    }
}
'''
files["src/main/java/com/datawise/analytics/AnalyticsService.java"] = r'''package com.datawise.analytics;

import com.datawise.model.DataSet;
import java.util.*;

public class AnalyticsService {
    public Map<String, Double> summary(DataSet ds, int column) {
        List<Double> x = ds.numericValues(column);
        Map<String, Double> s = new LinkedHashMap<>();
        if (x.isEmpty()) return s;
        Collections.sort(x);
        int n = x.size();
        double mean = x.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = n > 1 ? x.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / (n - 1) : 0;
        s.put("Count", (double)n); s.put("Mean", mean); s.put("Median", percentile(x, .5));
        s.put("Minimum", x.get(0)); s.put("Q1", percentile(x, .25)); s.put("Q3", percentile(x, .75));
        s.put("Maximum", x.get(n - 1)); s.put("Range", x.get(n - 1) - x.get(0));
        s.put("Variance", variance); s.put("Standard deviation", Math.sqrt(variance));
        s.put("IQR", percentile(x, .75) - percentile(x, .25));
        return s;
    }
    private double percentile(List<Double> sorted, double p) {
        if (sorted.size() == 1) return sorted.get(0);
        double index = p * (sorted.size() - 1);
        int lo = (int)Math.floor(index), hi = (int)Math.ceil(index);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (index - lo);
    }
    public double correlation(DataSet ds, int a, int b) {
        List<Double> x = new ArrayList<>(), y = new ArrayList<>();
        for (List<String> row : ds.getRows()) {
            try {
                if (!DataSet.isMissing(row.get(a)) && !DataSet.isMissing(row.get(b))) {
                    x.add(Double.parseDouble(row.get(a).trim()));
                    y.add(Double.parseDouble(row.get(b).trim()));
                }
            } catch (NumberFormatException ignored) { }
        }
        if (x.size() < 2) return Double.NaN;
        double mx = x.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double my = y.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double cov = 0, vx = 0, vy = 0;
        for (int i = 0; i < x.size(); i++) {
            double dx = x.get(i)-mx, dy = y.get(i)-my;
            cov += dx*dy; vx += dx*dx; vy += dy*dy;
        }
        return vx == 0 || vy == 0 ? Double.NaN : cov / Math.sqrt(vx * vy);
    }
}
'''
files["src/main/java/com/datawise/ml/LinearRegressionService.java"] = r'''package com.datawise.ml;

import com.datawise.model.DataSet;
import java.util.*;

public class LinearRegressionService {
    public record Result(double slope, double intercept, double mae, double rmse, double rSquared, int testCount) {
        public double predict(double x) { return slope * x + intercept; }
    }
    public Result train(DataSet ds, int xColumn, int yColumn) {
        List<double[]> pairs = new ArrayList<>();
        for (List<String> row : ds.getRows()) {
            try {
                double x = Double.parseDouble(row.get(xColumn).trim());
                double y = Double.parseDouble(row.get(yColumn).trim());
                if (Double.isFinite(x) && Double.isFinite(y)) pairs.add(new double[]{x,y});
            } catch (Exception ignored) { }
        }
        if (pairs.size() < 4) throw new IllegalArgumentException("At least four valid numeric rows are required.");
        // Deterministic 80/20 split: first records train, final records test.
        int split = Math.max(2, (int)Math.floor(pairs.size() * .8));
        split = Math.min(split, pairs.size() - 1);
        double mx = 0, my = 0;
        for (int i=0; i<split; i++) { mx += pairs.get(i)[0]; my += pairs.get(i)[1]; }
        mx /= split; my /= split;
        double numerator=0, denominator=0;
        for (int i=0; i<split; i++) {
            double dx=pairs.get(i)[0]-mx;
            numerator += dx*(pairs.get(i)[1]-my); denominator += dx*dx;
        }
        if (denominator == 0) throw new IllegalArgumentException("Input feature is constant in the training data.");
        double slope=numerator/denominator, intercept=my-slope*mx;
        double abs=0, sq=0, actualMean=0;
        int n=pairs.size()-split;
        for (int i=split; i<pairs.size(); i++) actualMean += pairs.get(i)[1];
        actualMean /= n;
        double total=0, residual=0;
        for (int i=split; i<pairs.size(); i++) {
            double actual=pairs.get(i)[1], predicted=slope*pairs.get(i)[0]+intercept;
            abs += Math.abs(actual-predicted); sq += Math.pow(actual-predicted,2);
            residual += Math.pow(actual-predicted,2); total += Math.pow(actual-actualMean,2);
        }
        double r2 = total == 0 ? Double.NaN : 1-residual/total;
        return new Result(slope,intercept,abs/n,Math.sqrt(sq/n),r2,n);
    }
}
'''
files["src/main/java/com/datawise/MainApp.java"] = r'''package com.datawise;

import com.datawise.analytics.AnalyticsService;
import com.datawise.ml.LinearRegressionService;
import com.datawise.model.DataSet;
import com.datawise.service.CsvService;
import javafx.application.Application;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class MainApp extends Application {
    private final CsvService csv = new CsvService();
    private final AnalyticsService analytics = new AnalyticsService();
    private final LinearRegressionService regression = new LinearRegressionService();
    private DataSet data;
    private Path originalPath;
    private final Label datasetLabel = new Label("No dataset loaded");
    private final Label status = new Label("Ready");
    private final TableView<ObservableList<String>> table = new TableView<>();
    private final Label recordsKpi = new Label("0"), columnsKpi = new Label("0"), numericKpi = new Label("0"),
            missingKpi = new Label("0"), duplicateKpi = new Label("0");
    private final ComboBox<String> statsColumn = new ComboBox<>(), chartColumn = new ComboBox<>(),
            chartType = new ComboBox<>(), xColumn = new ComboBox<>(), yColumn = new ComboBox<>();
    private final TextArea output = new TextArea();

    @Override public void start(Stage stage) {
        stage.setTitle("DataWise Analytics Dashboard");
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        VBox sidebar = new VBox(10);
        sidebar.getStyleClass().add("sidebar");
        Label brand = new Label("DATAWISE");
        brand.getStyleClass().add("brand");
        Label sub = new Label("ANALYTICS STUDIO");
        sub.getStyleClass().add("sub-brand");
        Button importButton = navButton("Import dataset", () -> importCsv(stage));
        Button sampleButton = navButton("Load sample data", this::loadSample);
        Button overviewButton = navButton("Overview", () -> showOverview());
        Button exploreButton = navButton("Data explorer", () -> showExplorer());
        Button cleaningButton = navButton("Data cleaning", () -> showCleaning());
        Button statsButton = navButton("Statistical analysis", () -> showStatistics());
        Button chartsButton = navButton("Charts", () -> showCharts());
        Button predictionButton = navButton("Prediction lab", () -> showPrediction());
        Button exportButton = navButton("Export cleaned CSV", () -> exportCsv(stage));
        sidebar.getChildren().addAll(brand, sub, new Separator(), overviewButton, importButton, sampleButton,
                exploreButton, cleaningButton, statsButton, chartsButton, predictionButton, exportButton, new Region(), status);
        VBox.setVgrow(sidebar.getChildren().get(sidebar.getChildren().size()-2), Priority.ALWAYS);
        HBox top = new HBox(16, new Label("DataWise"), new Region(), datasetLabel);
        top.setAlignment(Pos.CENTER_LEFT); top.getStyleClass().add("topbar");
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        root.setLeft(sidebar); root.setTop(top);
        root.setCenter(buildWelcome());
        Scene scene = new Scene(root, 1250, 790);
        var css = getClass().getResource("/com/datawise/css/dashboard.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setMinWidth(950); stage.setMinHeight(650); stage.setScene(scene); stage.show();
        loadSample();
    }
    private Button navButton(String text, Runnable action) {
        Button b = new Button(text); b.getStyleClass().add("nav-button"); b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> action.run()); return b;
    }
    private Node buildWelcome() {
        VBox box = new VBox(14, new Label("Welcome to DataWise"), new Label("Import a CSV file or load the sample sales dataset to begin."));
        box.setPadding(new Insets(30)); return box;
    }
    private void setContent(Node node) { ((BorderPane) table.getScene().getRoot()).setCenter(node); }
    private VBox page(String title, String description) {
        VBox box = new VBox(14); box.setPadding(new Insets(24));
        Label heading = new Label(title); heading.getStyleClass().add("page-title");
        Label desc = new Label(description); desc.getStyleClass().add("muted");
        box.getChildren().addAll(heading, desc); return box;
    }
    private void showOverview() {
        VBox box = page("Overview", "A live summary of the active dataset.");
        GridPane cards = new GridPane(); cards.setHgap(12); cards.setVgap(12);
        String[] names={"Records","Columns","Numeric columns","Missing values","Duplicate rows"};
        Label[] values={recordsKpi,columnsKpi,numericKpi,missingKpi,duplicateKpi};
        for(int i=0;i<names.length;i++){ VBox c=new VBox(8,new Label(names[i]),values[i]); c.getStyleClass().add("kpi-card"); cards.add(c,i%3,i/3); }
        box.getChildren().addAll(cards, new Label("Quick start"), new Label("Use the left navigation to explore, clean, chart, and model your data."));
        refreshKpis(); setContent(new ScrollPane(box));
    }
    private void showExplorer() {
        VBox box = page("Data explorer", "Search and inspect imported rows. Sort by clicking any column header.");
        TextField search = new TextField(); search.setPromptText("Search rows across all columns...");
        search.textProperty().addListener((o,a,b)->filterTable(b));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        box.getChildren().addAll(search, table);
        VBox.setVgrow(table, Priority.ALWAYS); rebuildTable(""); setContent(box);
    }
    private void rebuildTable(String query) {
        table.getColumns().clear(); table.getItems().clear();
        if(data==null) return;
        List<String> headers=data.getColumns();
        for(int c=0;c<headers.size();c++){
            final int idx=c; TableColumn<ObservableList<String>,String> col=new TableColumn<>(headers.get(c));
            col.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().get(idx)));
            col.setPrefWidth(130); table.getColumns().add(col);
        }
        for(List<String> row:data.getRows()) {
            if(query==null || query.isBlank() || row.stream().anyMatch(v->v.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))))
                table.getItems().add(FXCollections.observableArrayList(row));
        }
        table.setPlaceholder(new Label(data.rowCount()==0?"Dataset has no records":"No matching rows"));
    }
    private void filterTable(String q) { rebuildTable(q); }
    private void showCleaning() {
        VBox box=page("Data cleaning","Operations modify the active in-memory dataset. The imported source file is never overwritten.");
        ComboBox<String> column=new ComboBox<>(); populateColumns(column); column.setPromptText("Select column");
        Button dedupe=new Button("Remove duplicate rows"); dedupe.setOnAction(e->{if(data!=null){data.removeDuplicates(); refreshAll(); status.setText("Duplicates removed");}});
        Button fillMean=new Button("Fill missing values with numeric mean"); fillMean.setOnAction(e->{int i=column.getSelectionModel().getSelectedIndex(); if(data!=null&&i>=0){if(!data.isNumericColumn(i)){alert("Select a numeric column first.");return;}data.fillMissingMean(i);refreshAll();status.setText("Missing numeric values filled");}});
        TextField replacement=new TextField("Unknown"); replacement.setPromptText("Replacement text");
        Button fillText=new Button("Fill missing values with text"); fillText.setOnAction(e->{int i=column.getSelectionModel().getSelectedIndex();if(data!=null&&i>=0){data.fillMissingCategory(i,replacement.getText());refreshAll();}});
        Button rename=new Button("Rename selected column"); TextField newName=new TextField(); newName.setPromptText("New column name");
        rename.setOnAction(e->{int i=column.getSelectionModel().getSelectedIndex();if(data!=null&&i>=0&&!newName.getText().isBlank()){data.renameColumn(i,newName.getText());refreshAll();}});
        Button remove=new Button("Remove selected column"); remove.setOnAction(e->{int i=column.getSelectionModel().getSelectedIndex();if(data!=null&&i>=0){data.removeColumn(i);refreshAll();}});
        box.getChildren().addAll(new Label("Selected column"),column, dedupe, new Separator(), new Label("Numeric missing values"),fillMean,
                new Label("Categorical missing values"),replacement,fillText,new Separator(),newName,rename,remove);
        setContent(new ScrollPane(box));
    }
    private void showStatistics() {
        VBox box=page("Statistical analysis","Descriptive statistics are calculated from valid numeric values in the selected column.");
        populateColumns(statsColumn); statsColumn.setPromptText("Choose numeric column");
        Button calculate=new Button("Calculate statistics");
        calculate.setOnAction(e->{int i=statsColumn.getSelectionModel().getSelectedIndex(); if(i<0||data==null){alert("Choose a column.");return;}
            if(!data.isNumericColumn(i)){alert("The selected column is not numeric.");return;}
            Map<String,Double> s=analytics.summary(data,i); output.setText(s.entrySet().stream().map(en->String.format(Locale.US,"%-22s %,.4f",en.getKey(),en.getValue())).collect(Collectors.joining("\n")));
        });
        output.setEditable(false); output.setPrefRowCount(14);
        box.getChildren().addAll(statsColumn,calculate,output); setContent(box);
    }
    private void showCharts() {
        VBox box=page("Charts and visualizations","Choose a chart and numeric column. The chart uses values from the active dataset.");
        populateColumns(chartColumn); chartColumn.setPromptText("Numeric column");
        chartType.getItems().setAll("Bar chart","Line chart","Histogram");
        chartType.getSelectionModel().selectFirst();
        Button draw=new Button("Generate chart"); StackPane chartPane=new StackPane(); chartPane.setMinHeight(400);
        draw.setOnAction(e->{int idx=chartColumn.getSelectionModel().getSelectedIndex();if(idx<0||data==null){alert("Choose a column.");return;}
            if(!data.isNumericColumn(idx)){alert("Choose a numeric column.");return;}
            List<Double> vals=data.numericValues(idx); String type=chartType.getValue(); XYChart chart;
            if("Histogram".equals(type)){
                int bins=Math.min(12,Math.max(4,(int)Math.sqrt(vals.size()))); double min=vals.stream().mapToDouble(v->v).min().orElse(0), max=vals.stream().mapToDouble(v->v).max().orElse(1);
                double width=(max-min)/bins;if(width==0)width=1;
                int[] counts=new int[bins]; for(double v:vals)counts[Math.min(bins-1,Math.max(0,(int)((v-min)/width)))]++;
                CategoryAxis x=new CategoryAxis(); NumberAxis y=new NumberAxis(); BarChart<String,Number> bc=new BarChart<>(x,y); bc.setTitle("Histogram: "+data.getColumns().get(idx)); bc.setLegendVisible(false);
                XYChart.Series<String,Number> s=new XYChart.Series<>();for(int i=0;i<bins;i++)s.getData().add(new XYChart.Data<>(String.format(Locale.US,"%.1f",min+i*width),counts[i]));bc.getData().add(s);chart=bc;
            } else if("Line chart".equals(type)){
                NumberAxis x=new NumberAxis();NumberAxis y=new NumberAxis();LineChart<Number,Number> lc=new LineChart<>(x,y);lc.setTitle(data.getColumns().get(idx)+" by row");lc.setCreateSymbols(false);
                XYChart.Series<Number,Number>s=new XYChart.Series<>();for(int i=0;i<vals.size();i++)s.getData().add(new XYChart.Data<>(i+1,vals.get(i)));lc.getData().add(s);chart=lc;
            } else {
                CategoryAxis x=new CategoryAxis();NumberAxis y=new NumberAxis();BarChart<String,Number> bc=new BarChart<>(x,y);bc.setTitle(data.getColumns().get(idx)+" by row (first 50)");bc.setLegendVisible(false);
                XYChart.Series<String,Number>s=new XYChart.Series<>();for(int i=0;i<Math.min(50,vals.size());i++)s.getData().add(new XYChart.Data<>(Integer.toString(i+1),vals.get(i)));bc.getData().add(s);chart=bc;
            }
            chart.setAnimated(false);chartPane.getChildren().setAll(chart);
        });
        box.getChildren().addAll(new HBox(10,chartType,chartColumn,draw),chartPane); VBox.setVgrow(chartPane,Priority.ALWAYS); setContent(box);
    }
    private void showPrediction() {
        VBox box=page("Prediction lab","Simple linear regression with a deterministic holdout test set. Estimates are not guarantees.");
        populateColumns(xColumn);populateColumns(yColumn);xColumn.setPromptText("Input feature (X)");yColumn.setPromptText("Target (Y)");
        TextField input=new TextField();input.setPromptText("Enter X value to predict Y");
        Button train=new Button("Train and evaluate");TextArea result=new TextArea();result.setEditable(false);result.setPrefRowCount(12);
        final LinearRegressionService.Result[] model={null};
        train.setOnAction(e->{try{
            int x=xColumn.getSelectionModel().getSelectedIndex(),y=yColumn.getSelectionModel().getSelectedIndex();
            if(data==null||x<0||y<0||x==y)throw new IllegalArgumentException("Choose two different columns.");
            if(!data.isNumericColumn(x)||!data.isNumericColumn(y))throw new IllegalArgumentException("Both columns must be numeric.");
            model[0]=regression.train(data,x,y);var r=model[0];
            result.setText(String.format(Locale.US,"Model: y = %.6f x + %.6f%nTest records: %d%nMAE: %.4f%nRMSE: %.4f%nR²: %.4f%n%nMetrics are evaluated on the held-out final 20%% of valid rows.",r.slope(),r.intercept(),r.testCount(),r.mae(),r.rmse(),r.rSquared()));
        }catch(Exception ex){alert(ex.getMessage());}});
        Button predict=new Button("Predict");Label prediction=new Label();
        predict.setOnAction(e->{try{if(model[0]==null)throw new IllegalArgumentException("Train a model first.");double x=Double.parseDouble(input.getText().trim());prediction.setText(String.format(Locale.US,"Estimated Y: %.4f",model[0].predict(x)));}catch(Exception ex){alert("Enter a valid number and train a model first.");}});
        box.getChildren().addAll(new HBox(10,xColumn,yColumn,train),result,new HBox(10,input,predict,prediction));setContent(new ScrollPane(box));
    }
    private void populateColumns(ComboBox<String> combo) {
        combo.getItems().clear();if(data!=null)combo.getItems().setAll(data.getColumns());
    }
    private void importCsv(Stage stage) {
        FileChooser fc=new FileChooser();fc.setTitle("Import CSV dataset");fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("CSV and text files","*.csv","*.txt"),new FileChooser.ExtensionFilter("All files","*.*"));
        java.io.File file=fc.showOpenDialog(stage);if(file==null)return;
        status.setText("Importing...");
        Task<DataSet> task=new Task<>(){@Override protected DataSet call() throws Exception{return csv.read(file.toPath());}};
        task.setOnSucceeded(e->{data=task.getValue();originalPath=file.toPath();datasetLabel.setText(data.getName());refreshAll();showOverview();status.setText("Imported "+data.rowCount()+" rows");});
        task.setOnFailed(e->{status.setText("Import failed");alert("Could not import file:\n"+task.getException().getMessage());});
        Thread t=new Thread(task,"csv-import");t.setDaemon(true);t.start();
    }
    private void loadSample() {
        List<String> headers=List.of("Month","Region","Revenue","Units","MarketingSpend");
        List<List<String>> rows=new ArrayList<>();
        String[] months={"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
        String[] regions={"North","South","East","West"};
        for(int i=0;i<48;i++) rows.add(new ArrayList<>(List.of(months[i%12],regions[i%4],Integer.toString(1200+i*87+(i%5)*130),Integer.toString(35+i*3+(i%4)*7),Integer.toString(150+i*18+(i%3)*25))));
        data=new DataSet("sample-sales.csv",headers,rows);originalPath=null;datasetLabel.setText(data.getName());refreshAll();showOverview();status.setText("Sample dataset loaded");
    }
    private void refreshKpis() {
        recordsKpi.setText(data==null?"0":Integer.toString(data.rowCount()));
        columnsKpi.setText(data==null?"0":Integer.toString(data.columnCount()));
        numericKpi.setText(data==null?"0":Integer.toString(data.numericColumnCount()));
        missingKpi.setText(data==null?"0":Integer.toString(data.missingCount()));
        duplicateKpi.setText(data==null?"0":Integer.toString(data.duplicateCount()));
    }
    private void refreshAll() { refreshKpis();rebuildTable("");populateColumns(statsColumn);populateColumns(chartColumn);populateColumns(xColumn);populateColumns(yColumn); }
    private void exportCsv(Stage stage) {
        if(data==null){alert("Load a dataset first.");return;}
        FileChooser fc=new FileChooser();fc.setTitle("Export dataset");fc.setInitialFileName("datawise-cleaned.csv");fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV file","*.csv"));
        java.io.File f=fc.showSaveDialog(stage);if(f==null)return;
        try{csv.write(data,f.toPath());status.setText("Exported "+f.getName());}catch(Exception ex){alert("Export failed: "+ex.getMessage());}
    }
    private void alert(String message) { Alert a=new Alert(Alert.AlertType.INFORMATION);a.setTitle("DataWise");a.setHeaderText(null);a.setContentText(message==null?"An unexpected error occurred.":message);a.showAndWait(); }
    @Override public void stop() { }
    public static void main(String[] args) { launch(args); }
}
'''
files["src/main/resources/com/datawise/css/dashboard.css"] = r'''.root-pane { -fx-font-family: "Segoe UI", "Arial"; -fx-background-color: #f4f7fb; }
.sidebar { -fx-background-color: #111c35; -fx-padding: 22 14 18 14; -fx-pref-width: 220; }
.brand { -fx-font-size: 23px; -fx-font-weight: bold; -fx-text-fill: #ffffff; }
.sub-brand { -fx-font-size: 10px; -fx-text-fill: #a9b7d4; -fx-letter-spacing: 1.5px; }
.nav-button { -fx-background-color: transparent; -fx-text-fill: #d9e2f5; -fx-alignment: CENTER_LEFT; -fx-padding: 11 12; -fx-background-radius: 8; -fx-cursor: hand; }
.nav-button:hover { -fx-background-color: #263657; -fx-text-fill: white; }
.topbar { -fx-background-color: white; -fx-padding: 18 24; -fx-border-color: #e5eaf2; -fx-border-width: 0 0 1 0; }
.page-title { -fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #17243d; }
.muted { -fx-text-fill: #68758d; }
.kpi-card { -fx-background-color: white; -fx-background-radius: 12; -fx-padding: 20; -fx-min-width: 190; -fx-effect: dropshadow(gaussian, #1d355710, 12, 0, 0, 3); }
.kpi-card .label:first-child { -fx-text-fill: #6b7890; -fx-font-size: 13px; }
.kpi-card .label:last-child { -fx-text-fill: #1b2c4a; -fx-font-size: 27px; -fx-font-weight: bold; }
.button { -fx-background-color: #365ee8; -fx-text-fill: white; -fx-background-radius: 7; -fx-padding: 9 14; -fx-cursor: hand; }
.button:hover { -fx-background-color: #274bd0; }
.text-field, .combo-box, .text-area { -fx-background-radius: 7; -fx-border-radius: 7; -fx-border-color: #d8e0ed; -fx-background-color: white; }
.table-view { -fx-background-color: white; -fx-border-color: #e0e6ef; }
'''
files["sample-data/sales.csv"] = "Month,Region,Revenue,Units,MarketingSpend\nJan,North,1200,35,150\nFeb,South,1450,42,175\nMar,East,1610,48,190\nApr,West,1800,51,220\nMay,North,2100,63,250\nJun,South,2250,67,275\nJul,East,2400,72,300\nAug,West,2550,77,315\nSep,North,2700,80,340\nOct,South,2900,86,365\nNov,East,3100,91,390\nDec,West,3350,98,420\n"
files["sample-data/students.csv"] = "Student,StudyHours,Attendance,Score\nAsha,2,75,58\nRavi,3,80,64\nMina,4,82,70\nArun,5,88,76\nSara,6,91,82\nDev,7,93,87\nIsha,8,95,91\nKiran,4,84,72\n"
files["sample-data/employees.csv"] = "Employee,Department,Salary,Experience\nAarav,Engineering,65000,2\nDiya,Sales,52000,3\nKabir,Engineering,78000,5\nMira,HR,48000,4\nNeel,Finance,71000,6\nTara,Sales,56000,4\n"
files["sample-data/country_gdp.csv"] = "Country,Year,GDP_Billion,Population_Million\nIndia,2021,3176,1408\nIndia,2022,3385,1417\nIndia,2023,3567,1426\nJapan,2021,4940,125.7\nJapan,2022,4231,124.9\nJapan,2023,4213,124.5\nGermany,2021,4259,83.2\nGermany,2022,4082,83.4\nGermany,2023,4456,84.5\n"
files["sample-data/inventory.csv"] = "Product,Category,Stock,UnitPrice\nLaptop,Electronics,28,799.99\nMouse,Accessories,140,19.99\nMonitor,Electronics,44,189.5\nKeyboard,Accessories,85,49.99\nDesk,Office,18,129.99\nHeadphones,Electronics,67,79.99\n"
files["src/test/java/com/datawise/analytics/AnalyticsServiceTest.java"] = r'''package com.datawise.analytics;

import com.datawise.model.DataSet;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsServiceTest {
    @Test void calculatesMeanAndMedian() {
        DataSet ds = new DataSet("test.csv", List.of("x"), List.of(List.of("1"),List.of("2"),List.of("3"),List.of("4")));
        var summary = new AnalyticsService().summary(ds,0);
        assertEquals(2.5, summary.get("Mean"), 1e-9);
        assertEquals(2.5, summary.get("Median"), 1e-9);
    }
    @Test void correlationOfPerfectLineIsOne() {
        DataSet ds = new DataSet("test.csv", List.of("x","y"), List.of(List.of("1","2"),List.of("2","4"),List.of("3","6")));
        assertEquals(1.0, new AnalyticsService().correlation(ds,0,1), 1e-9);
    }
}
'''
files["src/test/java/com/datawise/model/DataSetTest.java"] = r'''package com.datawise.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DataSetTest {
    @Test void removesDuplicateRows() {
        DataSet ds = new DataSet("test.csv", List.of("x"), List.of(List.of("1"),List.of("1"),List.of("2")));
        assertEquals(1, ds.duplicateCount());
        ds.removeDuplicates();
        assertEquals(2, ds.rowCount());
    }
    @Test void countsMissingValues() {
        DataSet ds = new DataSet("test.csv", List.of("x","y"), List.of(List.of("","2"),List.of("1","NA")));
        assertEquals(2, ds.missingCount());
    }
}
'''
files["README.md"] = r'''# DataWise Analytics Dashboard (Java)

A database-free desktop data analytics starter project built with Java 21, JavaFX, Maven, Apache Commons CSV, and JUnit 5.

## Implemented features
- Import CSV/TXT files through a native file chooser (CSV parsing runs in a background task).
- Built-in sample sales dataset and additional sample CSVs in `sample-data/`.
- Live KPI counts: records, columns, numeric columns, missing values, duplicate rows.
- Searchable table preview with sortable column headers.
- Data cleaning: remove duplicate rows, fill missing numeric values with mean, fill missing values with a chosen category, rename/remove columns.
- Numeric descriptive statistics: count, mean, median, min/max, range, variance, standard deviation, quartiles, IQR.
- JavaFX bar, line, and histogram charts from the active dataset.
- Linear regression with a deterministic holdout split and MAE, RMSE, R²; enter a new X value for a prediction.
- CSV export for the current (possibly cleaned) in-memory dataset.
- JUnit tests for key analytics and cleaning functions.

## Requirements
- JDK 21 or newer
- Maven 3.9+
- VS Code with the Extension Pack for Java, or IntelliJ IDEA
- Internet access on first Maven build to download dependencies

No database or API key is required. Imported files are read locally. Export writes only when you choose a save location.

## Run on Windows 11
1. Install a JDK 21 distribution (for example, Temurin or Microsoft Build of OpenJDK).
2. Install Maven and make sure `java -version` and `mvn -version` work in a new terminal.
3. Extract this project folder.
4. Open the `DataWiseAnalytics` folder in VS Code.
5. In the VS Code terminal, run:

   ```powershell
   mvn clean test
   mvn javafx:run
   ```

JavaFX Maven dependencies are declared in `pom.xml`; you do not need to manually download JavaFX SDK.

## IntelliJ IDEA
1. Choose **Open** and select the folder containing `pom.xml`.
2. Allow Maven to import dependencies.
3. Open the Maven tool window and run `javafx:run`, or use the built-in terminal with `mvn javafx:run`.
4. Run tests with `mvn test`.

## Common issues
- **`mvn` is not recognized:** install Maven, add its `bin` directory to PATH, then restart VS Code.
- **Wrong Java version:** run `java -version`; select JDK 21 in VS Code/IDEA and ensure `JAVA_HOME` points to it.
- **JavaFX runtime errors:** use the Maven run command rather than manually running `MainApp` as a plain Java class.
- **Dependency download failure:** check internet/proxy settings and retry `mvn -U clean test`.
- **No data in a chart:** select a numeric column with valid numeric values.

## Notes and limitations
This is a functional learning/portfolio baseline, not a full commercial BI platform. It currently supports CSV/TXT, not Excel workbooks. The table has JavaFX sorting and search but not server-side filtering or pagination. The chart set includes bar, line, and histogram; pie, scatter, area, box plot, correlation heatmap UI, PDF reporting, dark-mode toggle, undo, and a distributable installer are not implemented in this baseline. `AnalyticsService.correlation` is implemented and tested but not yet exposed as a dedicated heatmap screen. Regression uses the final 20% of valid rows as a holdout; for time-ordered data, this split may be appropriate, but results can vary by dataset and do not guarantee future outcomes.

## Project structure
```text
DataWiseAnalytics/
├── pom.xml
├── README.md
├── sample-data/
└── src/
    ├── main/java/com/datawise/
    │   ├── MainApp.java
    │   ├── model/DataSet.java
    │   ├── service/CsvService.java
    │   ├── analytics/AnalyticsService.java
    │   └── ml/LinearRegressionService.java
    ├── main/resources/com/datawise/css/dashboard.css
    └── test/java/com/datawise/
```
'''
for rel, content in files.items():
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
# fix root lookup bug: scene root is BorderPane, but table.scene can be null before displayed; setContent invoked after stage so okay.
zip_path = pathlib.Path("/mnt/data/DataWiseAnalytics.zip")
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for p in root.rglob("*"):
        if p.is_file():
            z.write(p, p.relative_to(root.parent))
print(f"Created {len(files)} project files.")
print(f"ZIP: {zip_path} ({zip_path.stat().st_size:,} bytes)")
