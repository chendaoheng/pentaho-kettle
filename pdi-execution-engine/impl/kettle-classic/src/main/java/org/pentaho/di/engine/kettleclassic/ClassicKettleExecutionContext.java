package org.pentaho.di.engine.kettleclassic;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import org.pentaho.di.engine.api.IExecutionContext;
import org.pentaho.di.engine.api.IExecutionResult;
import org.pentaho.di.engine.api.ITransformation;
import org.pentaho.di.engine.api.reporting.ILogicalModelElement;
import org.pentaho.di.engine.api.reporting.IMaterializedModelElement;
import org.pentaho.di.engine.api.reporting.IModelElement;
import org.pentaho.di.engine.api.reporting.IReportingEvent;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.TransExecutionConfiguration;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.metastore.api.IMetaStore;
import org.reactivestreams.Publisher;
import rx.Observable;
import rx.RxReactiveStreams;
import rx.Scheduler;
import rx.schedulers.Schedulers;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

/**
 * Created by nbaker on 1/5/17.
 */
public class ClassicKettleExecutionContext implements IExecutionContext {
  private Map<String, Object> parameters = new HashMap<String, Object>();
  private Map<String, Object> environment = new HashMap<String, Object>();
  private final ClassicKettleEngine engine;
  private Scheduler scheduler = Schedulers.io();
  private ITransformation logicalTrans;
  private TransExecutionConfiguration executionConfiguration = new TransExecutionConfiguration();
  private String[] arguments;
  private IMetaStore metaStore;
  private Repository repository;

  private Cache<PublisherKey, Publisher> publishers = CacheBuilder.newBuilder().build();

  private Map<ILogicalModelElement, IMaterializedModelElement> logical2MaterializedMap = new HashMap<>();

  private TransMeta transMeta;
  private ClassicTransformation materializedTrans;

  public TransMeta getTransMeta() {
    return transMeta;
  }

  public void setTransMeta( TransMeta transMeta ) {
    this.transMeta = transMeta;
  }

  public ClassicTransformation getMaterializedTransformation() {
    return materializedTrans;
  }


  public ClassicKettleExecutionContext( ClassicKettleEngine engine, ITransformation trans ) {
    this.engine = engine;
    this.logicalTrans = trans;

    // Materialize Trans and populate Logical -> Materialized map
    materializedTrans = ClassicUtils.materialize(  this, logicalTrans );

    List<IModelElement> modelElements = new ArrayList<>( );
    modelElements.add( materializedTrans );
    modelElements.addAll( materializedTrans.getOperations() );
    modelElements.addAll( materializedTrans.getHops() );

    logical2MaterializedMap.putAll( modelElements.stream().map(IMaterializedModelElement.class::cast).collect( Collectors.toMap(
      IMaterializedModelElement::getLogicalElement, Function.identity()) ) );

  }

  @Override
  @SuppressWarnings( "unchecked" )
  public <S extends ILogicalModelElement, D extends Serializable>
  Publisher<IReportingEvent<S, D>> eventStream( S source, Class<D> type ) {
    try {
      // Cache cannot use these method type parameters. Having to cast. Creator function can insert nulls to prevent
      // reattempts
      return publishers.get( new PublisherKey( source, type ),
        () -> Optional.ofNullable( logical2MaterializedMap.get( source ) )
          .map( iMaterializedModelElement -> iMaterializedModelElement.getPublisher( type ).get() ).orElse(
            RxReactiveStreams.toPublisher( Observable.empty() )) );
    } catch ( ExecutionException e ) {
      throw new RuntimeException( e );
    }
  }

  @Override public Collection<ILogicalModelElement> getReportingSources() {
    return ImmutableList.of();
  }

  @Override public CompletableFuture<IExecutionResult> execute() {
    return engine.execute( this );
  }

  @Override public Map<String, Object> getParameters() {
    return parameters;
  }

  @Override public Map<String, Object> getEnvironment() {
    return environment;
  }

  @Override public ITransformation getTransformation() {
    return logicalTrans;
  }

  public void setTransformation( ITransformation transformation ) {
    this.logicalTrans = transformation;
  }


  public void setExecutionConfiguration( TransExecutionConfiguration executionConfiguration ) {
    this.executionConfiguration = executionConfiguration;
  }

  public TransExecutionConfiguration getExecutionConfiguration() {
    return executionConfiguration;
  }

  public void setMetaStore( IMetaStore metaStore ) {
    this.metaStore = metaStore;
  }

  public void setRepository( Repository repository ) {
    this.repository = repository;
  }

  public void setArguments( String[] arguments ) {
    this.arguments = arguments;
  }

  public IMetaStore getMetaStore() {
    return metaStore;
  }

  public Repository getRepository() {
    return repository;
  }

  public void setScheduler( Scheduler scheduler ) {
    this.scheduler = scheduler;
  }

  public Scheduler getScheduler() {
    return scheduler;
  }



  private static class PublisherKey {
    private final ILogicalModelElement source;
    private final Class<? extends Serializable> eventType;

    public PublisherKey( ILogicalModelElement source, Class<? extends Serializable> eventType ) {
      this.source = source;
      this.eventType = eventType;
    }

    @Override public int hashCode() {
      int result = source.hashCode();
      result = 31 * result + eventType.hashCode();
      return result;
    }

  }
}