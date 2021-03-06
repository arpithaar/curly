/* 
 * Copyright 2015 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.ags.curly.model;

import com.adobe.ags.curly.CurlyApp;
import com.sun.javafx.collections.ObservableListWrapper;
import java.util.ArrayList;
import java.util.Collections;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.LongBinding;
import javafx.beans.binding.NumberBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyListProperty;
import javafx.beans.property.ReadOnlyListWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;

public abstract class RunnerResult<T extends RunnerResult> {

    AtomicDouble completeCounter = new AtomicDouble();
    AtomicDouble successCounter = new AtomicDouble();
    final private DoubleProperty percentSuccess = new SimpleDoubleProperty(0);
    final private DoubleProperty percentComplete = new SimpleDoubleProperty(0);
    private DoubleProperty totalSuccess = new SimpleDoubleProperty(0);
    private DoubleProperty totalComplete = new SimpleDoubleProperty(0);
    final private BooleanBinding completedBinding = Bindings.greaterThanOrEqual(percentComplete, 0.9999999);
    final private BooleanBinding completelySuccessfulBinding = Bindings.greaterThanOrEqual(percentSuccess(), 0.9999999);
    final private LongBinding durationBinding;
    StringBinding percentCompleteBinding = Bindings.createStringBinding(()->
            String.format("%.1f%%",100.0*percentComplete().get()),percentComplete());
    StringBinding percentSuccessBinding = Bindings.createStringBinding(()->
            String.format("%.1f%%",100.0*percentSuccess().get()),percentSuccess());

    final private BooleanProperty started = new SimpleBooleanProperty(false);
    final private ObservableList<ObservableValue> reportRow = new ObservableListWrapper<>(new ArrayList<>());
    final protected ObservableList<T> details = new ObservableListWrapper<>(Collections.synchronizedList(new ArrayList<>()));
    final private LongProperty startTime = new SimpleLongProperty(-1);
    final private LongProperty endTime = new SimpleLongProperty(-1);

    public RunnerResult() {
        durationBinding = Bindings.createLongBinding(() -> {
            if (startTime.get() == -1) {
                return 0L;
            } else if (endTime.get() == -1) {
                return System.currentTimeMillis() - startTime.get();
            } else {
                return endTime.get() - startTime.get();
            }
        }, startTime, endTime);

        started.addListener((prop, oldVal, newVal) -> {
            if (newVal && startTime.get() < 0) {
                startTime.set(System.currentTimeMillis());
            }
            invalidateBindings();
        });
        completed().addListener(((prop, oldVal, newVal) -> {
            if (newVal && endTime.get() < 0) {
                endTime.set(System.currentTimeMillis());
            }
            invalidateBindings();
        }));
    }

    public LongProperty startTime() {
        return startTime;
    }

    public LongProperty endTime() {
        return endTime;
    }

    public NumberBinding getDuration() {
        return durationBinding;
    }

    public DoubleProperty percentSuccess() {
        return percentSuccess;
    }
    
    public StringBinding percentSuccessString() {
        return percentSuccessBinding;
    }
    
    public StringBinding percentCompleteString() {
        return percentCompleteBinding;
    }

    public DoubleProperty percentComplete() {
        return percentComplete;
    }

    public BooleanProperty started() {
        return started;
    }

    final public BooleanBinding completed() {
        return completedBinding;
    }

    public BooleanBinding completelySuccessful() {
        return completelySuccessfulBinding;
    }

    private void doInvalidateBindings() {
        synchronized (details) {
            details.forEach(RunnerResult::invalidateBindings);
        }
        completelySuccessfulBinding.invalidate();
        completedBinding.invalidate();
        durationBinding.invalidate();
        percentCompleteBinding.invalidate();
        percentSuccessBinding.invalidate();
    }
    
    public void invalidateBindings() {
        if (Platform.isFxApplicationThread()) {
            doInvalidateBindings();
        } else {
            Platform.runLater(this::doInvalidateBindings);
        }
    }

    public ObservableList<ObservableValue> reportRow() {
        return reportRow;
    }

    public ReadOnlyListProperty<T> getDetails() {
        return new ReadOnlyListWrapper<>(details);
    }

    public void addDetail(T detail) {
        synchronized (details) {
            details.add(detail);
        }
        Platform.runLater(() -> trackSummaryAgainstAdditonalDetail(detail));
    }
    
    public void updateComputations() {
        percentSuccess().unbind();
        percentComplete().unbind();
        completeCounter.getAndSet(0.0);
        successCounter.getAndSet(0.0);
        details.forEach(detail -> {
            detail.updateComputations();
            completeCounter.getAndAdd(detail.percentComplete().get());
            successCounter.getAndAdd(detail.percentSuccess().get());
        });
        
        CurlyApp.runNow(()-> {
            totalComplete.set(completeCounter.get());
            totalSuccess.set(successCounter.get());
            percentSuccess().set(successCounter.get() / details.size());
            percentComplete().set(completeCounter.get() / details.size());
        });
        
    }

    private void trackSummaryAgainstAdditonalDetail(T detail) {
        detail.percentComplete().addListener((ObservableValue<? extends Number> prop, Number oldVal, Number newVal) -> {
            double oldV = (oldVal == null) ? 0 : oldVal.doubleValue();
            double newV = newVal.doubleValue();
            completeCounter.getAndAdd(-oldV);
            completeCounter.getAndAdd(newV);
            Platform.runLater(()->totalComplete.set(completeCounter.get()));
        });
        detail.percentSuccess().addListener((ObservableValue<? extends Number> prop, Number oldVal, Number newVal) -> {
            double oldV = (oldVal == null) ? 0 : oldVal.doubleValue();
            double newV = newVal.doubleValue();
            successCounter.getAndAdd(-oldV);
            successCounter.getAndAdd(newV);
            Platform.runLater(()->totalSuccess.set(successCounter.get()));
        });
        synchronized (details) {
            percentSuccess().bind(totalSuccess.divide(details.size()));
            percentComplete().bind(totalComplete.divide(details.size()));
        }
    }

    abstract public String toHtml(int level);
}
