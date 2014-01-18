/*
 * NotifierController.java - Copyright(c) 2013 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Dec 6, 2013
 */

package org.noroomattheinn.visibletesla;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import jfxtras.labs.scene.control.BigDecimalField;
import org.apache.commons.lang3.StringUtils;
import org.noroomattheinn.tesla.ChargeState;
import org.noroomattheinn.tesla.SnapshotState;
import org.noroomattheinn.tesla.Tesla;
import org.noroomattheinn.tesla.Vehicle;
import org.noroomattheinn.utils.MailGun;
import org.noroomattheinn.utils.Utils;
import org.noroomattheinn.visibletesla.trigger.Predicate;
import org.noroomattheinn.visibletesla.trigger.Trigger;
import org.noroomattheinn.visibletesla.trigger.RW;

/**
 * NotifierController
 *
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */



public class NotifierController extends BaseController {

/*------------------------------------------------------------------------------
 *
 * Constants and Enums
 * 
 *----------------------------------------------------------------------------*/

    private static final String NotifySEKey = "NOTIFY_SE";
    private static final String NotifyCSKey = "NOTIFY_CS";
    private static final String NotifySpeedKey = "NOTIFY_SPEED";
    private static final String NotifySOCHitsKey = "NOTIFY_SOC_HITS";
    private static final String NotifySOCFallsKey = "NOTIFY_SOC_FALLS";
    
    private static final MailGun mailer = new MailGun("api", "key-2x6kwt4t-f4qcy9nb9wmo4yed681ogr6");

/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/
    
    private Trigger<BigDecimal> speedHitsTrigger;
    private Trigger<String>     seTrigger;
    private Trigger<BigDecimal> socHitsTrigger;
    private Trigger<BigDecimal> socFallsTrigger;
    private Trigger<String>     csTrigger;
    private List<Trigger>       allTriggers = new ArrayList<>();
    private boolean             useMiles = true;
    
/*------------------------------------------------------------------------------
 *
 * UI Elements
 * 
 *----------------------------------------------------------------------------*/
        
    @FXML private CheckBox chargeState;
    @FXML private ComboBox<String> csOptions;

    @FXML private CheckBox schedulerEvent;

    @FXML private CheckBox socFalls;
    @FXML private BigDecimalField socFallsField;
    @FXML private Slider socFallsSlider;

    @FXML private CheckBox socHits;
    @FXML private BigDecimalField socHitsField;
    @FXML private Slider socHitsSlider;

    @FXML private CheckBox speedHits;
    @FXML private BigDecimalField speedHitsField;
    @FXML private Slider speedHitsSlider;
    @FXML private Label speedUnitsLabel;
    
/*------------------------------------------------------------------------------
 *
 * UI Action Handlers
 * 
 *----------------------------------------------------------------------------*/

    @FXML void enabledEvent(ActionEvent event) {
        // TO DO: Remove this. This should happen automatically by the
        // Trigger which is listening for change events on the property
        // associated with the checkbox
    }

/*------------------------------------------------------------------------------
 *
 * Methods overriden from BaseController
 * 
 *----------------------------------------------------------------------------*/

    @Override protected void fxInitialize() {
        bindBidrectional(speedHitsField, speedHitsSlider);
        bindBidrectional(socHitsField, socHitsSlider);
        bindBidrectional(socFallsField, socFallsSlider);
    }

    @Override protected void prepForVehicle(Vehicle v) {
        if (differentVehicle()) {
            // TO DO: Remove old triggers!
            socHitsTrigger = new Trigger<>(
                appContext, socHits.selectedProperty(), RW.bdHelper,
                "SOC", NotifySOCHitsKey,  Predicate.Type.HitsOrExceeds,
                socHitsField.numberProperty(), new BigDecimal(88.0));
            socFallsTrigger = new Trigger<>(
                appContext, socFalls.selectedProperty(), RW.bdHelper,
                "SOC", NotifySOCFallsKey, Predicate.Type.FallsBelow,
                socFallsField.numberProperty(), new BigDecimal(50.0));
            speedHitsTrigger = new Trigger<>(
                appContext, speedHits.selectedProperty(), RW.bdHelper,
                "Speed", NotifySpeedKey, Predicate.Type.HitsOrExceeds,
                speedHitsField.numberProperty(), new BigDecimal(70.0));
            seTrigger = new Trigger<>(
                appContext, schedulerEvent.selectedProperty(), RW.stringHelper,
                "Scheduler", NotifySEKey, Predicate.Type.AnyChange,
                new SimpleObjectProperty<>("Anything"), "Anything");
            csTrigger = new Trigger<>(
                appContext, chargeState.selectedProperty(), RW.stringHelper,
                "Charge State", NotifyCSKey, Predicate.Type.Becomes,
                csOptions.valueProperty(), csOptions.itemsProperty().get().get(0));
            allTriggers.addAll(Arrays.asList(
                    speedHitsTrigger, socHitsTrigger, socFallsTrigger,
                    csTrigger, seTrigger));
            
            for (Trigger t : allTriggers) { t.init(); }

            startListening();
        }
        
        useMiles = appContext.lastKnownGUIState.get().distanceUnits.equalsIgnoreCase("mi/hr");
        if (appContext.simulatedUnits.get() != null)
            useMiles = (appContext.simulatedUnits.get() == Utils.UnitType.Imperial);
        speedUnitsLabel.setText(useMiles ? "mph" : "km/h");
    }

    @Override protected void refresh() { }

    @Override protected void reflectNewState() {}

/*------------------------------------------------------------------------------
 *
 * PRIVATE - Utility Functions
 * 
 *----------------------------------------------------------------------------*/
    
/*------------------------------------------------------------------------------
 *
 * PRIVATE - Methods for detecting changes and testing triggers
 * 
 *----------------------------------------------------------------------------*/
    
    private void startListening() {
        appContext.lastKnownChargeState.addListener(csListener);
        appContext.lastKnownSnapshotState.addListener(ssListener);
        appContext.schedulerActivityReport.addListener(schedListener);
    }
    
    private ChangeListener<String> schedListener = new ChangeListener<String>() {
        @Override public void changed(
                ObservableValue<? extends String> ov, String old, String cur) {
            String msg = seTrigger.evalPredicate(cur);
            if (msg != null) notifyUser(msg);
        }
    };

    private ChangeListener<ChargeState.State> csListener = new ChangeListener<ChargeState.State>() {
        @Override public synchronized void  changed(
                ObservableValue<? extends ChargeState.State> ov,
                ChargeState.State old, ChargeState.State cur) {
            notifyUser(csTrigger.evalPredicate(cur.chargingState.name()));
        }
    };
            
    private ChangeListener<SnapshotState.State> ssListener = new ChangeListener<SnapshotState.State>() {
        long lastSpeedNotification = 0;
        
        @Override public void changed(
                ObservableValue<? extends SnapshotState.State> ov,
                SnapshotState.State old, SnapshotState.State cur) {
            notifyUser(socHitsTrigger.evalPredicate(new BigDecimal(cur.soc)));
            notifyUser(socFallsTrigger.evalPredicate(new BigDecimal(cur.soc)));
            double speed = useMiles ? cur.speed : cur.speed * Utils.mToK(cur.speed);
            String msg =  (speedHitsTrigger.evalPredicate(new BigDecimal(speed)));
            if (msg != null) {
                if (System.currentTimeMillis() - lastSpeedNotification > 30 * 60 * 1000) {
                    notifyUser(msg);
                    lastSpeedNotification = System.currentTimeMillis();
                }
            }
        }
    };
    
    public void notifyUser(String msg) {
        sendNotification(appContext.prefs.notificationAddress.get(), msg);
    }
    
    private static final int SubjectLength = 30;
    public static void sendNotification(String addr, String msg) {
        if (msg == null) return;
        Tesla.logger.fine("Notification: " + msg);
        if (addr == null || addr.length() == 0) {
            Tesla.logger.warning(
                    "Unable to send a notification because no address was specified: " + msg);
        }
        String date = String.format("%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS", new Date());
        int msgLen = msg.length();
        String subject = StringUtils.left(msg, SubjectLength) + (msgLen > SubjectLength ? "..." : "");
        String body = date + "\n" + msg;
        if (!mailer.send(addr, subject, body)) {
            Tesla.logger.warning("Failed sending message to: " + addr + ": " + msg);
        }
    }
    
    private void bindBidrectional(final BigDecimalField bdf, final Slider slider) {
        bdf.setFormat(new DecimalFormat("##0.0"));
        bdf.setStepwidth(BigDecimal.valueOf(0.5));
        bdf.setNumber(new BigDecimal(osd(slider.getValue())));

        slider.valueProperty().addListener(new ChangeListener<Number>() {
            @Override public void changed(
                    ObservableValue<? extends Number> ov, Number t, Number t1) {
                double val = osd(t1.doubleValue());
                slider.setValue(val);
                bdf.setNumber(new BigDecimal(val));
            }
        });

        bdf.numberProperty().addListener(new ChangeListener<BigDecimal>() {
            @Override public void changed(
                    ObservableValue<? extends BigDecimal> ov, BigDecimal t, BigDecimal t1) {
                double val = osd(t1.doubleValue());
                slider.setValue(val);
                bdf.setNumber(new BigDecimal(val));
            }
        });
    }

    private double osd(double val) {
        return Math.round(val * 10.0)/10.0;
    }
}

    
class TimeSelectorControl {
    private final ObjectProperty<Calendar> calendarProperty;
    
    TimeSelectorControl(final ComboBox<String> hour, final ComboBox<String> min, final ComboBox<String> amPM) {
        Calendar initial = new GregorianCalendar();
        initial.set(Calendar.HOUR, Integer.valueOf(hour.valueProperty().get()));
        initial.set(Calendar.MINUTE, Integer.valueOf(min.valueProperty().get()));
        initial.set(Calendar.AM_PM, amPM.valueProperty().get().equals("AM") ? Calendar.AM : Calendar.PM);
        calendarProperty = new SimpleObjectProperty<>(initial);
        
        hour.valueProperty().addListener(new ChangeListener<String>() {
            @Override public void changed(ObservableValue<? extends String> ov, String t, String t1) {
                Calendar c = copy(calendarProperty.get());
                c.set(Calendar.HOUR, Integer.valueOf(t1));
                calendarProperty.set(c);
            }
        });
        min.valueProperty().addListener(new ChangeListener<String>() {
            @Override public void changed(ObservableValue<? extends String> ov, String t, String t1) {
                Calendar c = copy(calendarProperty.get());
                c.set(Calendar.MINUTE, Integer.valueOf(t1));
                calendarProperty.set(c);
            }
        });
        amPM.valueProperty().addListener(new ChangeListener<String>() {
            @Override public void changed(ObservableValue<? extends String> ov, String t, String t1) {
                Calendar c = copy(calendarProperty.get());
                c.set(Calendar.AM_PM, t1.equals("AM") ? Calendar.AM : Calendar.PM);
                calendarProperty.set(c);
            }
        });
        
        calendarProperty.addListener(new ChangeListener<Calendar>() {
            @Override  public void changed(
                    ObservableValue<? extends Calendar> ov, Calendar t, Calendar t1) {
                int h = t1.get(Calendar.HOUR);
                if (h == 0) h = 12;
                int m = t1.get(Calendar.MINUTE);
                boolean isPM = t1.get(Calendar.AM_PM) == Calendar.PM;
                hour.getSelectionModel().select(String.format("%02d", h));
                min.getSelectionModel().select(String.format("%02d", m));
                amPM.getSelectionModel().select(isPM ? "PM" : "AM");
            }
        });
    }

    TimeSelectorControl(HBox parent) {
        this(Utils.<ComboBox<String>>cast(parent.getChildren().get(0)),
             Utils.<ComboBox<String>>cast(parent.getChildren().get(1)),
             Utils.<ComboBox<String>>cast(parent.getChildren().get(2)));
    }
    
    public ObjectProperty<Calendar> calendarProperty() { return calendarProperty; }
    
    private Calendar copy(Calendar orig) {
        GregorianCalendar c = new GregorianCalendar(orig.getTimeZone());
        c.setTime(orig.getTime());
        return c;
    }
}
