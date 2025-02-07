package de.aitools.ie.articles;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import org.apache.uima.UIMAFramework;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.cas.CAS;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceSpecifier;
import org.apache.uima.util.XMLInputSource;

import de.aitools.ie.uima.feature.IFeatureType;
import de.aitools.ie.uima.feature.aggregate.TopicAndStyleFeatures;
import de.aitools.ie.uima.type.article.ArticleMetaData;
import de.aitools.ie.uima.usage.DatasetBalancer;
import de.aitools.ie.uima.usage.WekaFeatureFileWriter;

public class FeatureExtractor {

  public static final String FEATURES_CONFIG = "properties/feature-config.properties";
  public static final String NORMALIZATION_CONFIG = "properties/normalization.properties";

  private final TargetClass targetClass;

  public FeatureExtractor(final TargetClass targetClass) throws IOException {
    this.targetClass = Objects.requireNonNull(targetClass);
  }

  protected Properties loadProperties(final String name) throws IOException {
    final Properties properties = new Properties();
    properties.load(this.getClass().getClassLoader().getResourceAsStream(name));
    return properties;
  }

  protected void extractAll(final Path dataRootDirectory, final Path outputRootDirectory) throws IOException {
    // Durch alle Verzeichnisse iterieren, um die ARFF-Dateien zu extrahieren
    final Path trainingDataDirectory = dataRootDirectory.resolve("training");

    // Eine einzige ARFF-Datei für alle Daten (Training + Validation)
    Path fullArffFile = outputRootDirectory.resolve("full-corpus.arff");

    // Alles in die Datei extrahieren
    this.extractAndWriteToSingleFile(trainingDataDirectory, fullArffFile);
  }

  private void extractAndWriteToSingleFile(final Path dataDirectory, final Path outputFile) throws IOException {
    System.out.println("Extracting data from: " + dataDirectory + " -> " + outputFile);

    // FeatureName setzen
    final List<String> featureNames = this.determineFeatures(dataDirectory);

    // Bereitstellen der Feature-Vektoren und schreiben in die ARFF-Datei
    this.extract(dataDirectory, outputFile, featureNames, true);
  }

  public void extract(final Path trainingDataDirectory, final Path trainingOutputDirectory) throws IOException {
    System.out.println("Extract " + trainingDataDirectory + " -> " + trainingOutputDirectory);

    final List<String> featureNames = this.determineFeatures(trainingDataDirectory);
    this.extract(trainingDataDirectory, trainingOutputDirectory, featureNames, true);
  }

  protected void extract(final Path dataDirectory, final Path outputDirectory,
                         final List<String> featureNames, final boolean isTrainingSet) throws IOException {
    System.out.println("  Extract " + dataDirectory + " -> " + outputDirectory);
    final IFeatureType features = this.initializeFeatures();
    features.initializeFeatureComputation(featureNames,
            this.loadProperties(FEATURES_CONFIG),
            this.loadProperties(NORMALIZATION_CONFIG));

    final FeatureVectors featureVectors = this.computeFeatureVectors(dataDirectory, features);

    if (isTrainingSet) {
      final DatasetBalancer balancer = new DatasetBalancer(false, true);
      balancer.balanceInstances(featureVectors.values, featureVectors.classValues);
    }

    final boolean isSparse = false;
    final int decimalPlaces = 3;
    final boolean isNumeric = false;
    final WekaFeatureFileWriter writer = new WekaFeatureFileWriter(outputDirectory.toString(),
            "webis", "stylometric-inquiry", isSparse, decimalPlaces, isNumeric);
    writer.writeFeatureFile(featureVectors.values, featureVectors.classValues, dataDirectory.toString(),
            ArticleMetaData.class.getName(), this.targetClass.getClassFeature(),
            this.targetClass.getClassMapping(), featureNames);
  }

  protected IFeatureType initializeFeatures() throws IOException {
    final IFeatureType features = new TopicAndStyleFeatures();
    features.initializeFeatureDetermination(this.loadProperties(FEATURES_CONFIG));
    return features;
  }

  protected FeatureVectors computeFeatureVectors(final Path dataDirectory, final IFeatureType features) {
    final FeatureVectors featureVectors = new FeatureVectors();
    try {
      final CollectionReader collectionReader = this.createCollectionReader(dataDirectory);
      final AnalysisEngine analysisEngine = this.createAnalysisEngine();

      final CAS cas = analysisEngine.newCAS();
      while (collectionReader.hasNext()) {
        collectionReader.getNext(cas);
        final JCas jcas = cas.getJCas();
        final String classValue = this.targetClass.getClassValue(jcas);
        if (classValue != null) {
          analysisEngine.process(jcas);
          final ArticleMetaData metaData = (ArticleMetaData) jcas.getAnnotationIndex(ArticleMetaData.type).iterator().next();
          featureVectors.add(features.computeNormalizedFeatureValues(jcas, metaData.getBegin(), metaData.getEnd()), classValue);
        }
      }

      collectionReader.destroy();
      analysisEngine.destroy();
      return featureVectors;
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected List<String> determineFeatures(final Path dataDirectory) {
    try {
      final IFeatureType features = this.initializeFeatures();
      final CollectionReader collectionReader = this.createCollectionReader(dataDirectory);
      final AnalysisEngine analysisEngine = this.createAnalysisEngine();

      final CAS cas = analysisEngine.newCAS();
      while (collectionReader.hasNext()) {
        collectionReader.getNext(cas);
        final JCas jcas = cas.getJCas();
        final String classValue = this.targetClass.getClassValue(jcas);
        if (classValue != null) {
          analysisEngine.process(jcas);
          this.updateCandidateFeatures(features, jcas);
        }
      }

      final List<String> featureNames = features.determineFeatures(
              this.loadProperties(FEATURES_CONFIG),
              this.loadProperties(NORMALIZATION_CONFIG));

      collectionReader.destroy();
      analysisEngine.destroy();
      return featureNames;
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected void updateCandidateFeatures(final IFeatureType features, final JCas jcas) {
    final ArticleMetaData metaData = (ArticleMetaData) jcas.getAnnotationIndex(ArticleMetaData.type).iterator().next();
    features.updateCandidateFeatures(jcas, metaData.getBegin(), metaData.getEnd());
  }

  protected CollectionReader createCollectionReader(final Path dataDirectory) {
    try {
      final XMLInputSource xmlInputSource = new XMLInputSource(
              this.getClass().getClassLoader().getResource("uima-descriptors/collection-readers/UIMAAnnotationFileReader.xml"));
      final ResourceSpecifier specifier = UIMAFramework.getXMLParser().parseResourceSpecifier(xmlInputSource);
      final CollectionReader collectionReader = UIMAFramework.produceCollectionReader(specifier);
      collectionReader.setConfigParameterValue("InputDirectory", dataDirectory.toString());
      collectionReader.setConfigParameterValue("IncludeSubdirectories", true);
      collectionReader.reconfigure();
      return collectionReader;
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected AnalysisEngine createAnalysisEngine() {
    try {
      final XMLInputSource xmlInputSource = new XMLInputSource(
              this.getClass().getClassLoader().getResource("uima-descriptors/aggregate-AEs/StylometricInquiryPipeline.xml"));
      final ResourceSpecifier specifier = UIMAFramework.getXMLParser().parseResourceSpecifier(xmlInputSource);
      final AnalysisEngine analysisEngine = UIMAFramework.produceAnalysisEngine(specifier);
      analysisEngine.reconfigure();
      return analysisEngine;
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected class FeatureVectors {
    public final List<List<Double>> values;
    public final List<String> classValues;

    public FeatureVectors() {
      this.values = new ArrayList<>();
      this.classValues = new ArrayList<>();
    }

    public void add(final List<Double> values, final String classValue) {
      // Überprüfen ob FeatureVektor schon besteht
      for (int i = 0; i < this.values.size(); i++) {
        if (this.values.get(i).equals(values) && this.classValues.get(i).equals(classValue)) {
          // Falls bereits existens -> verwerfen
          return;
        }
      }
      // Hinzufügen falls keine Dopplung vorhanden
      this.values.add(values);
      this.classValues.add(classValue);
    }
  }

  public static void main(final String[] args) throws IOException {
    final TargetClass targetClass = TargetClass.valueOf(args[0]);
    final Path dataRootDirectory = Paths.get(args[1]);
    final Path outputRootDirectory = Paths.get(args[2]);

    final FeatureExtractor extractor = new FeatureExtractor(targetClass);
    extractor.extractAll(dataRootDirectory, outputRootDirectory);
  }
}
