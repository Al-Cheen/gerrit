// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.config;

import static java.time.ZoneId.systemDefault;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.common.Nullable;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoValue
public abstract class ScheduleConfig {
  private static final Logger log = LoggerFactory.getLogger(ScheduleConfig.class);

  private static final long MISSING_CONFIG = -1L;
  private static final long INVALID_CONFIG = -2L;
  private static final String KEY_INTERVAL = "interval";
  private static final String KEY_STARTTIME = "startTime";

  public static ScheduleConfig create(Config config, String section) {
    return builder(config, section).build();
  }

  public static ScheduleConfig.Builder builder(Config config, String section) {
    return new AutoValue_ScheduleConfig.Builder()
        .setNow(ZonedDateTime.now(systemDefault()))
        .setKeyInterval(KEY_INTERVAL)
        .setKeyStartTime(KEY_STARTTIME)
        .setConfig(config)
        .setSection(section);
  }

  abstract Config config();

  abstract String section();

  @Nullable
  abstract String subsection();

  abstract String keyInterval();

  abstract String keyStartTime();

  abstract ZonedDateTime now();

  @Memoized
  public Optional<Schedule> schedule() {
    long interval = computeInterval(config(), section(), subsection(), keyInterval());

    long initialDelay;
    if (interval > 0) {
      initialDelay =
          computeInitialDelay(config(), section(), subsection(), keyStartTime(), now(), interval);
    } else {
      initialDelay = interval;
    }

    if (isInvalidOrMissing(interval, initialDelay)) {
      return Optional.empty();
    }

    return Optional.of(Schedule.create(interval, initialDelay));
  }

  private boolean isInvalidOrMissing(long interval, long initialDelay) {
    String key = section() + (subsection() != null ? "." + subsection() : "");
    if (interval == MISSING_CONFIG && initialDelay == MISSING_CONFIG) {
      log.info("No schedule configuration for \"{}\".", key);
      return true;
    }

    if (interval == MISSING_CONFIG) {
      log.error(
          "Incomplete schedule configuration for \"{}\" is ignored. Missing value for \"{}\".",
          key,
          key + "." + keyInterval());
      return true;
    }

    if (initialDelay == MISSING_CONFIG) {
      log.error(
          "Incomplete schedule configuration for \"{}\" is ignored. Missing value for \"{}\".",
          key,
          key + "." + keyStartTime());
      return true;
    }

    if (interval <= 0 || initialDelay < 0) {
      log.error("Invalid schedule configuration for \"{}\" is ignored. ", key);
      return true;
    }

    return false;
  }

  @Override
  public String toString() {
    StringBuilder b = new StringBuilder();
    b.append(formatValue(keyInterval()));
    b.append(", ");
    b.append(formatValue(keyStartTime()));
    return b.toString();
  }

  private String formatValue(String key) {
    StringBuilder b = new StringBuilder();
    b.append(section());
    if (subsection() != null) {
      b.append(".");
      b.append(subsection());
    }
    b.append(".");
    b.append(key);
    String value = config().getString(section(), subsection(), key);
    if (value != null) {
      b.append(" = ");
      b.append(value);
    } else {
      b.append(": NA");
    }
    return b.toString();
  }

  private static long computeInterval(
      Config rc, String section, String subsection, String keyInterval) {
    try {
      return ConfigUtil.getTimeUnit(
          rc, section, subsection, keyInterval, MISSING_CONFIG, TimeUnit.MILLISECONDS);
    } catch (IllegalArgumentException e) {
      return INVALID_CONFIG;
    }
  }

  private static long computeInitialDelay(
      Config rc,
      String section,
      String subsection,
      String keyStartTime,
      ZonedDateTime now,
      long interval) {
    long delay = MISSING_CONFIG;
    String start = rc.getString(section, subsection, keyStartTime);
    try {
      if (start != null) {
        DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("[E ]HH:mm").withLocale(Locale.US);
        LocalTime firstStartTime = LocalTime.parse(start, formatter);
        ZonedDateTime startTime = now.with(firstStartTime);
        try {
          DayOfWeek dayOfWeek = formatter.parse(start, DayOfWeek::from);
          startTime = startTime.with(dayOfWeek);
        } catch (DateTimeParseException ignored) {
          // Day of week is an optional parameter.
        }
        startTime = startTime.truncatedTo(ChronoUnit.MINUTES);
        delay = Duration.between(now, startTime).toMillis() % interval;
        if (delay <= 0) {
          delay += interval;
        }
      }
    } catch (IllegalArgumentException e2) {
      delay = INVALID_CONFIG;
    }
    return delay;
  }

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setConfig(Config config);

    public abstract Builder setSection(String section);

    public abstract Builder setSubsection(@Nullable String subsection);

    public abstract Builder setKeyInterval(String keyInterval);

    public abstract Builder setKeyStartTime(String keyStartTime);

    @VisibleForTesting
    abstract Builder setNow(ZonedDateTime now);

    public abstract ScheduleConfig build();
  }

  @AutoValue
  public abstract static class Schedule {
    /** Number of milliseconds between events. */
    public abstract long interval();

    /**
     * Milliseconds between constructor invocation and first event time.
     *
     * <p>If there is any lag between the constructor invocation and queuing the object into an
     * executor the event will run later, as there is no method to adjust for the scheduling delay.
     */
    public abstract long initialDelay();

    static Schedule create(long interval, long initialDelay) {
      return new AutoValue_ScheduleConfig_Schedule(interval, initialDelay);
    }
  }
}