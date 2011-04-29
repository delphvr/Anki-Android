/****************************************************************************************
 * Copyright (c) 2009 Daniel Svärd <daniel.svard@gmail.com>                             *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.text.format.DateFormat;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

/**
 * A card is a presentation of a fact, and has two sides: a question and an answer. Any number of fields can appear on
 * each side. When you add a fact to Anki, cards which show that fact are generated. Some models generate one card,
 * others generate more than one.
 *
 * @see http://ichi2.net/anki/wiki/KeyTermsAndConcepts#Cards
 * 
 * 
 * Type: 0=lapsed, 1=due, 2=new, 3=drilled
 * Queue: under normal circumstances, same as type.
 * -1=suspended, -2=user buried, -3=sched buried (rev early, etc)
 * Ordinal: card template # for fact
 * Position: sorting position, only for new cards
 * Flags: unused; reserved for future use
 */


public class Card {

    // TODO: Javadoc.

    /** Card types. */
    public static final int TYPE_FAILED = 0;
    public static final int TYPE_REV = 1;
    public static final int TYPE_NEW = 2;

    /** Card states. */
    public static final String STATE_NEW = "new";
    public static final String STATE_YOUNG = "young";
    public static final String STATE_MATURE = "mature";

    /** Ease. */
    public static final int EASE_NONE = 0;
    public static final int EASE_FAILED = 1;
    public static final int EASE_HARD = 2;
    public static final int EASE_MID = 3;
    public static final int EASE_EASY = 4;

    /** Tags src constants. */
    public static final int TAGS_FACT = 0;
    public static final int TAGS_MODEL = 1;
    public static final int TAGS_TEMPL = 2;

    private static final int LEARNT_THRESHOLD = 7;
    public static final int MATURE_THRESHOLD = 21;

    private static final double MAX_TIMER = 60.0;

    // BEGIN SQL table entries
    private long mId; // Primary key
    private long mFactId; // Foreign key facts.id
    private long mCardModelId; // Foreign key cardModels.id
    // general
    private double mCreated = Utils.now();
    private double mModified = Utils.now();
    private String mQuestion = "";
    private String mAnswer = "";
    private int mFlags = 0;
    // ordering
    private int mOrdinal;
    private int mPosition;
    // scheduling data
    private int mType = TYPE_NEW;
    private int mQueue = TYPE_NEW;
    private double mLastInterval = 0;
    private double mInterval = 0;
    private double mDue;
    private double mFactor = Deck.INITIAL_FACTOR;
    // counters
    private int mReps = 0;
    private int mSuccessive = 0;
    private int mLapses = 0;
    // END SQL table entries

    public Deck mDeck;

    // BEGIN JOINed variables
    private CardModel mCardModel;
    private Fact mFact;
    private String[] mTagsBySrc;
    // END JOINed variables

    private double mTimerStarted;
    private double mTimerStopped;
    private double mFuzz = 0;

    // Leech flags, not read from database, only set to true during the actual suspension
    private boolean isLeechMarked;
    private boolean isLeechSuspended;

    public Card(Deck deck, Fact fact, CardModel cardModel, double created) {
        mTagsBySrc = new String[TAGS_TEMPL + 1];
        mTagsBySrc[TAGS_FACT] = "";
        mTagsBySrc[TAGS_MODEL] = "";
        mTagsBySrc[TAGS_TEMPL] = "";

        mId = Utils.genID();
        // New cards start as new & due
        mModified = Utils.now();
        if (Double.isNaN(created)) {
            mCreated = created;
            mDue = created;
        } else {
            mDue = mModified;
        }
        isLeechSuspended = false;
        mPosition = mDue;
        mDeck = deck;
        mFact = fact;
        if (fact != null) {
            mFactId = fact.getId();
        }
        mCardModel = cardModel;
        if (cardModel != null) {
            mCardModelId = cardModel.getId();
            mOrdinal = cardModel.getOrdinal();
        }
        mTimerStarted = Double.NaN;
    }


    public void setModified() {
        mModified = Utils.now();
    }


    public void startTimer() {
        mTimerStarted = Utils.now();
    }


    public void stopTimer() {
        mTimerStopped = Utils.now();
    }


    public void resumeTimer() {
        if (!Double.isNaN(mTimerStarted) && !Double.isNaN(mTimerStopped)) {
            mTimerStarted += Utils.now() - mTimerStopped;
            mTimerStopped = Double.NaN;
        } else {
            Log.i(AnkiDroidApp.TAG, "Card Timer: nothing to resume");
        }
    }


    public double userTime() {
        return Math.min((Utils.now() - mTimerStarted), MAX_TIMER);
    }


	/**
	 * Questions and answers
	 */
	public void rebuildQA(Deck deck) {
		rebuildQA(deck, true);
	}
	public void rebuildQA(Deck deck, boolean media) {
        // Format qa
		if (mFact != null && mCardModel != null) {
			HashMap<String, String> qa = CardModel.formatQA(mFact, mCardModel, _splitTags());

            if (media) {
                // Find old media references
                HashMap<String, Integer> files = new HashMap<String, Integer>();
                ArrayList<String> filesFromQA = Media.mediaFiles(mQuestion);
                filesFromQA.addAll(Media.mediaFiles(mAnswer));
                for (String f : filesFromQA) {
                    if (files.containsKey(f)) {
                        files.put(f, files.get(f) - 1);
                    } else {
                        files.put(f, -1);
                    }
                }
                // Update q/a
                mQuestion = qa.get("question");
                mAnswer = qa.get("answer");
                // Determine media delta
                filesFromQA = Media.mediaFiles(mQuestion);
                filesFromQA.addAll(Media.mediaFiles(mAnswer));
                for (String f : filesFromQA) {
                    if (files.containsKey(f)) {
                        files.put(f, files.get(f) + 1);
                    } else {
                        files.put(f, 1);
                    }
                }
                // Update media counts if we're attached to deck
                for (Entry<String, Integer> entry : files.entrySet()) {
                    Media.updateMediaCount(deck, entry.getKey(), entry.getValue());
                }
            } else {
                // Update q/a
                mQuestion = qa.get("question");
                mAnswer = qa.get("answer");
            }
            setModified();
		}
	}

    public Card(Deck deck) {
        this(deck, null, null, Double.NaN);
    }


    public Fact getFact() {
        if (mFact == null) {
            mFact = new Fact(mDeck, mFactId);
        }
        return mFact;
    }


    public double getFuzz() {
    	if (mFuzz == 0) {
    		genFuzz();
    	}
    	return mFuzz;
    }

    public void genFuzz() {
        // Random rand = new Random();
        // mFuzz = 0.95 + (0.1 * rand.nextDouble());
        mFuzz = (double) Math.random();
    }


    // XXX Unused
//    public String htmlQuestion(String type, boolean align) {
//        return null;
//    }
//
//
//    public String htmlAnswer(boolean align) {
//        return htmlQuestion("answer", align);
//    }


    public void updateStats(int ease, String state) {
        char[] newState = state.toCharArray();
        mReps += 1;
        if (ease > EASE_FAILED) {
            mSuccessive += 1;
        } else {
            mSuccessive = 0;
        }

        double delay = Math.min(totalTime(), MAX_TIMER);
        // Ignore any times over 60 seconds
        mReviewTime += delay;
        if (mAverageTime != 0) {
            mAverageTime = (mAverageTime + delay) / 2.0;
        } else {
            mAverageTime = delay;
        }
        // We don't track first answer for cards
        if (STATE_NEW.equalsIgnoreCase(state)) {
            newState = STATE_YOUNG.toCharArray();
        }

        // Update ease and yes/no count
        // We want attr to be of the form mYoungEase3
        newState[0] = Character.toUpperCase(newState[0]);
        String attr = "m" + String.valueOf(newState) + String.format("Ease%d", ease);
        try {
            Field f = this.getClass().getDeclaredField(attr);
            f.setInt(this, f.getInt(this) + 1);
        } catch (Exception e) {
            Log.e(AnkiDroidApp.TAG, "Failed to update " + attr + " : " + e.getMessage());
        }

        if (ease < EASE_HARD) {
            mNoCount += 1;
        } else {
            mYesCount += 1;
        }
        if (mFirstAnswered == 0) {
            mFirstAnswered = Utils.now();
        }
        setModified();
    }


    public void updateFactor(int ease, double averageFactor) {
        mLastFactor = mFactor;
        if (isNew()) {
            mFactor = averageFactor; // card is new, inherit beginning factor
        }
        if (isRev() && !isBeingLearnt()) {
            if (ease == EASE_FAILED) {
                mFactor -= 0.20;
            } else if (ease == EASE_HARD) {
                mFactor -= 0.15;
            }
        }
        if (ease == EASE_EASY) {
            mFactor += 0.10;
        }
        mFactor = Math.max(Deck.FACTOR_FOUR, mFactor);
    }


    public double adjustedDelay(int ease) {
	double dueCutoff = mDeck.getDueCutoff();
        if (isNew()) {
            return 0;
        }
	if (mReps != 0 && mSuccessive == 0) {
            return 0;
	}
        if (mCombinedDue <= dueCutoff) {
            return (dueCutoff - mDue) / 86400.0;
        } else {
            return (dueCutoff - mCombinedDue) / 86400.0;
        }
    }


    /**
     * Suspend this card.
     */
    public void suspend() {
        long[] ids = new long[1];
        ids[0] = mId;
        mDeck.suspendCards(ids);
        mDeck.reset();
    }


    /**
     * Unsuspend this card.
     */
    public void unsuspend() {
        long[] ids = new long[1];
        ids[0] = mId;
        mDeck.unsuspendCards(ids);
    }


    public boolean getSuspendedState() {
        return mDeck.getSuspendedState(mId);
    }

    /**
     * Delete this card.
     */
    public void delete() {
        List<String> ids = new ArrayList<String>();
        ids.add(Long.toString(mId));
        mDeck.deleteCards(ids);
    }


    public String getState() {
        if (isNew()) {
            return STATE_NEW;
        } else if (mInterval > MATURE_THRESHOLD) {
            return STATE_MATURE;
        }
        return STATE_YOUNG;
    }


    /**
     * Check if a card is a new card.
     * @return True if a card has never been seen before.
     */
    public boolean isNew() {
        return mReps == 0;
    }


    /**
     * Check if this is a revision of a successfully answered card.
     * @return True if the card was successfully answered last time.
     */
    public boolean isRev() {
        return mSuccessive != 0;
    }


    /**
     * Check if a card is being learnt.
     * @return True if card should use present intervals.
     */
    public boolean isBeingLearnt() {
        return mLastInterval < LEARNT_THRESHOLD;
    }


    public String[] _splitTags() {
        String[] tags = new String[]{
            getFact().getTags(),
            Model.getModel(mDeck, getFact().getModelId(), true).getTags(),
            getCardModel().getName()
        };
        return tags;
    }


    private String allTags() {
        // Non-Canonified string of fact and model tags
        if ((mTagsBySrc[TAGS_FACT].length() > 0) && (mTagsBySrc[TAGS_MODEL].length() > 0)) {
            return mTagsBySrc[TAGS_FACT] + "," + mTagsBySrc[TAGS_MODEL];
        } else if (mTagsBySrc[TAGS_FACT].length() > 0) {
            return mTagsBySrc[TAGS_FACT];
        } else {
            return mTagsBySrc[TAGS_MODEL];
        }
    }


    public boolean hasTag(String tag) {
        return (allTags().indexOf(tag) != -1);
    }


    public boolean isMarked() {
    	int markedId = mDeck.getMarketTagId();
    	if (markedId == -1) {
    		return false;
    	} else {
    		return (AnkiDatabaseManager.getDatabase(mDeck.getDeckPath()).queryScalar("SELECT count(*) FROM cardTags WHERE cardId = " + mId + " AND tagId = " + markedId + " LIMIT 1") != 0);
    	}
    }

    // FIXME: Should be removed. Calling code should directly interact with Model
    public CardModel getCardModel() {
        Model myModel = Model.getModel(mDeck, mCardModelId, false);
        return myModel.getCardModel(mCardModelId);
    }


    // Loading tags for this card. Needed when:
    // - we modify the card fields and need to update question and answer.
    // - we check is a card is marked
    public void loadTags() {
        Cursor cursor = null;

        int tagSrc = 0;

        // Flush tags
        for (int i = 0; i < mTagsBySrc.length; i++) {
            mTagsBySrc[i] = "";
        }

        try {
            cursor = AnkiDatabaseManager.getDatabase(mDeck.getDeckPath()).getDatabase().rawQuery(
                    "SELECT tags.tag, cardTags.src "
                    + "FROM cardTags JOIN tags ON cardTags.tagId = tags.id " + "WHERE cardTags.cardId = " + mId
                    + " AND cardTags.src in (" + TAGS_FACT + ", " + TAGS_MODEL + "," + TAGS_TEMPL + ") "
                    + "ORDER BY cardTags.id", null);
            while (cursor.moveToNext()) {
                tagSrc = cursor.getInt(1);
                if (mTagsBySrc[tagSrc].length() > 0) {
                    mTagsBySrc[tagSrc] += "," + cursor.getString(0);
                } else {
                    mTagsBySrc[tagSrc] += cursor.getString(0);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }


    public boolean fromDB(long id) {
        Cursor cursor = null;

        try {
            cursor = AnkiDatabaseManager.getDatabase(mDeck.getDeckPath()).getDatabase().rawQuery(
                    "SELECT id, factId, cardModelId, created, modified, tags, "
                            + "ordinal, question, answer, interval, lastInterval, "
                            + "due, lastDue, factor, lastFactor, firstAnswered, reps, "
                            + "successive, averageTime, reviewTime, youngEase0, youngEase1, "
                            + "youngEase2, youngEase3, youngEase4, matureEase0, matureEase1, "
                            + "matureEase2, matureEase3, matureEase4, yesCount, noCount, "
                            + "spaceUntil, isDue, type, combinedDue, relativeDelay " + "FROM cards " + "WHERE id = " + id, null);
            if (!cursor.moveToFirst()) {
                Log.w(AnkiDroidApp.TAG, "Card.java (fromDB(id)): No result from query.");
                return false;
            }

            mId = cursor.getLong(0);
            mFactId = cursor.getLong(1);
            mCardModelId = cursor.getLong(2);
            mCreated = cursor.getDouble(3);
            mModified = cursor.getDouble(4);
            mTags = cursor.getString(5);
            mOrdinal = cursor.getInt(6);
            mQuestion = cursor.getString(7);
            mAnswer = cursor.getString(8);
            mInterval = cursor.getDouble(9);
            mLastInterval = cursor.getDouble(10);
            mDue = cursor.getDouble(11);
            mLastDue = cursor.getDouble(12);
            mFactor = cursor.getDouble(13);
            mLastFactor = cursor.getDouble(14);
            mFirstAnswered = cursor.getDouble(15);
            mReps = cursor.getInt(16);
            mSuccessive = cursor.getInt(17);
            mAverageTime = cursor.getDouble(18);
            mReviewTime = cursor.getDouble(19);
            mYoungEase0 = cursor.getInt(20);
            mYoungEase1 = cursor.getInt(21);
            mYoungEase2 = cursor.getInt(22);
            mYoungEase3 = cursor.getInt(23);
            mYoungEase4 = cursor.getInt(24);
            mMatureEase0 = cursor.getInt(25);
            mMatureEase1 = cursor.getInt(26);
            mMatureEase2 = cursor.getInt(27);
            mMatureEase3 = cursor.getInt(28);
            mMatureEase4 = cursor.getInt(29);
            mYesCount = cursor.getInt(30);
            mNoCount = cursor.getInt(31);
            mSpaceUntil = cursor.getDouble(32);
            mIsDue = cursor.getInt(33);
            mType = cursor.getInt(34);
            mCombinedDue = cursor.getDouble(35);
            mRelativeDelay = cursor.getDouble(36);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // TODO: Should also read JOINed entries CardModel and Fact.

        return true;
    }

    // TODO: Remove Redundancies
    // I did a separated method because I don't want to interfere with other code while fact adding is not tested.
    public void addToDb(){
        if (isNew()) {
            mType = TYPE_NEW;
        } else if (isRev()) {
            mType = TYPE_REV;
        } else {
            mType = TYPE_FAILED;
        }

        ContentValues values = new ContentValues();
        values.put("id", mId);
        values.put("factId", mFactId);
        values.put("cardModelId", mCardModelId);
        values.put("created", mCreated);
        values.put("modified", mModified);
        values.put("tags", mTags);
        values.put("ordinal", mOrdinal);
        values.put("question", mQuestion);
        values.put("answer", mAnswer);
        values.put("interval", mInterval);
        values.put("lastInterval", mLastInterval);
        values.put("due", mDue);
        values.put("lastDue", mLastDue);
        values.put("factor", mFactor);
        values.put("lastFactor", mLastFactor);
        values.put("firstAnswered", mFirstAnswered);
        values.put("reps", mReps);
        values.put("successive", mSuccessive);
        values.put("averageTime", mAverageTime);
        values.put("reviewTime", mReviewTime);
        values.put("youngEase0", mYoungEase0);
        values.put("youngEase1", mYoungEase1);
        values.put("youngEase2", mYoungEase2);
        values.put("youngEase3", mYoungEase3);
        values.put("youngEase4", mYoungEase4);
        values.put("matureEase0", mMatureEase0);
        values.put("matureEase1", mMatureEase1);
        values.put("matureEase2", mMatureEase2);
        values.put("matureEase3", mMatureEase3);
        values.put("matureEase4", mMatureEase4);
        values.put("yesCount", mYesCount);
        values.put("noCount", mNoCount);
        values.put("spaceUntil", mSpaceUntil);
        values.put("isDue", mIsDue);
        values.put("type", mType);
        values.put("combinedDue", Math.max(mSpaceUntil, mDue));
        values.put("relativeDelay", 0.0);
        AnkiDatabaseManager.getDatabase(mDeck.getDeckPath()).insert(mDeck, "cards", null, values);

    }

    public void toDB() {

        ContentValues values = new ContentValues();
        values.put("factId", mFactId);
        values.put("cardModelId", mCardModelId);
        values.put("created", mCreated);
        values.put("modified", mModified);
        values.put("tags", mTags);
        values.put("ordinal", mOrdinal);
        values.put("question", mQuestion);
        values.put("answer", mAnswer);
        values.put("interval", mInterval);
        values.put("lastInterval", mLastInterval);
        values.put("due", mDue);
        values.put("lastDue", mLastDue);
        values.put("factor", mFactor);
        values.put("lastFactor", mLastFactor);
        values.put("firstAnswered", mFirstAnswered);
        values.put("reps", mReps);
        values.put("successive", mSuccessive);
        values.put("averageTime", mAverageTime);
        values.put("reviewTime", mReviewTime);
        values.put("youngEase0", mYoungEase0);
        values.put("youngEase1", mYoungEase1);
        values.put("youngEase2", mYoungEase2);
        values.put("youngEase3", mYoungEase3);
        values.put("youngEase4", mYoungEase4);
        values.put("matureEase0", mMatureEase0);
        values.put("matureEase1", mMatureEase1);
        values.put("matureEase2", mMatureEase2);
        values.put("matureEase3", mMatureEase3);
        values.put("matureEase4", mMatureEase4);
        values.put("yesCount", mYesCount);
        values.put("noCount", mNoCount);
        values.put("spaceUntil", mSpaceUntil);
        values.put("isDue", 0);
        values.put("type", mType);
        values.put("combinedDue", mCombinedDue);
        values.put("relativeDelay", mRelativeDelay);
        AnkiDatabaseManager.getDatabase(mDeck.getDeckPath()).update(mDeck, "cards", values, "id = " + mId, null, true);

        // TODO: Should also write JOINED entries: CardModel and Fact.
    }


    /**
     * Commit question and answer fields to database.
     */
    public void updateQAfields() {
        setModified();
        ContentValues values = new ContentValues();
        values.put("modified", mModified);
        values.put("question", mQuestion);
        values.put("answer", mAnswer);
        AnkiDatabaseManager.getDatabase(mDeck.getDeckPath()).update(mDeck, "cards", values, "id = " + mId, null);
    }


    public ContentValues getAnswerValues() {
	ContentValues values = new ContentValues();
        values.put("modified", mModified);
        values.put("interval", mInterval);
        values.put("lastInterval", mLastInterval);
        values.put("due", mDue);
        values.put("lastDue", mLastDue);
        values.put("factor", mFactor);
        values.put("lastFactor", mLastFactor);
        values.put("firstAnswered", mFirstAnswered);
        values.put("reps", mReps);
        values.put("successive", mSuccessive);
        values.put("averageTime", mAverageTime);
        values.put("reviewTime", mReviewTime);
        values.put("youngEase0", mYoungEase0);
        values.put("youngEase1", mYoungEase1);
        values.put("youngEase2", mYoungEase2);
        values.put("youngEase3", mYoungEase3);
        values.put("youngEase4", mYoungEase4);
        values.put("matureEase0", mMatureEase0);
        values.put("matureEase1", mMatureEase1);
        values.put("matureEase2", mMatureEase2);
        values.put("matureEase3", mMatureEase3);
        values.put("matureEase4", mMatureEase4);
        values.put("yesCount", mYesCount);
        values.put("noCount", mNoCount);
        values.put("type", mType);
        values.put("combinedDue", mCombinedDue);
        values.put("relativeDelay", mRelativeDelay);
	return values;
    }


    public long getId() {
        return mId;
    }


    public String getCardDetails(Context context) {
    	Resources res = context.getResources();
        StringBuilder builder = new StringBuilder();
        builder.append("<html><body text=\"#FFFFFF\"><table><colgroup><col span=\"1\" style=\"width: 40%;\"><col span=\"1\" style=\"width: 60%;\"></colgroup><tr><td>");
        builder.append(res.getString(R.string.card_details_question));
        builder.append("</td><td>");
        builder.append(Utils.stripHTML(mQuestion));
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_answer));
        builder.append("</td><td>");
        builder.append(Utils.stripHTML(mAnswer));
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_due));
        builder.append("</td><td>");
        if (mYesCount + mNoCount == 0) {
            builder.append("-");
        } else if (mCombinedDue < mDeck.getDueCutoff()) {
            builder.append(res.getString(R.string.card_details_now));
        } else {
            builder.append(Utils.getReadableInterval(context, (mCombinedDue - Utils.now()) / 86400.0, true));
        }
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_interval));
        builder.append("</td><td>");
        if (mInterval == 0) {
            builder.append("-");
        } else {
            builder.append(Utils.getReadableInterval(context, mInterval));
        }
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_ease));
        builder.append("</td><td>");
        double ease = Math.round(mFactor * 100);
        builder.append(ease / 100);
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_average_time));
        builder.append("</td><td>");
        if (mYesCount + mNoCount == 0) {
            builder.append("-");
        } else {
            builder.append(Utils.doubleToTime(mAverageTime));
        }
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_total_time));
        builder.append("</td><td>");
        builder.append(Utils.doubleToTime(mReviewTime));
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_yes_count));
        builder.append("</td><td>");
        builder.append(mYesCount);
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_no_count));
        builder.append("</td><td>");
        builder.append(mNoCount);
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_added));
        builder.append("</td><td>");
        builder.append(DateFormat.getDateFormat(context).format((long) (mCreated - mDeck.getUtcOffset()) * 1000l));
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_changed));
        builder.append("</td><td>");
        builder.append(DateFormat.getDateFormat(context).format((long) (mModified - mDeck.getUtcOffset()) * 1000l));
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_tags));
        builder.append("</td><td>");
        String tags = Arrays.toString(mDeck.allUserTags("WHERE id = " + mFactId));
        builder.append(tags.substring(1, tags.length() - 1));
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_model));
        builder.append("</td><td>");
        Model model = Model.getModel(mDeck, mCardModelId, false);
        builder.append(model.getName());
        builder.append("</td></tr><tr><td>");
        builder.append(res.getString(R.string.card_details_card_model));
        builder.append("</td><td>");
        builder.append(model.getCardModel(mCardModelId).getName());
        builder.append("</td></tr></html></body>");
    return builder.toString();
    }


    public void setLastInterval(double lastInterval) {
        mLastInterval = lastInterval;
    }


    public double getLastInterval() {
        return mLastInterval;
    }


    public void setInterval(double interval) {
        mInterval = interval;
    }


    public double getInterval() {
        return mInterval;
    }


    public void setLastFactor(double lastFactor) {
        mLastFactor = lastFactor;
    }


    public double getLastFactor() {
        return mLastFactor;
    }


    public double getFactor() {
        return mFactor;
    }


    public int getReps() {
        return mReps;
    }


    public int getYesCount() {
        return mYesCount;
    }


    public int getNoCount() {
        return mNoCount;
    }


    public void setQuestion(String question) {
        mQuestion = question;
    }


    public String getQuestion() {
        return mQuestion;
    }


    public void setAnswer(String answer) {
        mAnswer = answer;
    }


    public String getAnswer() {
        return mAnswer;
    }


    public void setModified(double modified) {
        mModified = modified;
    }


    public void setCombinedDue(double combinedDue) {
        mCombinedDue = combinedDue;
    }


    public double getCombinedDue() {
        return mCombinedDue;
    }


    public void setLastDue(double lastDue) {
        mLastDue = lastDue;
    }


    public void setDue(double due) {
        mDue = due;
    }


    public double getDue() {
        return mDue;
    }


    public void setIsDue(int isDue) {
        mIsDue = isDue;
    }


    /**
     * Check whether the card is due.
     * @return True if the card is due, false otherwise
     */
    public boolean isDue() {
        return (mIsDue == 1);
    }


    public long getFactId() {
        return mFactId;
    }


    public void setSpaceUntil(double spaceUntil) {
        mSpaceUntil = spaceUntil;
    }


    public void setRelativeDelay(double relativeDelay) {
        mRelativeDelay = relativeDelay;
    }


    public int getType() {
        return mType;
    }


    public void setType(int type) {
        mType = type;
    }


    public long getCardModelId() {
        return mCardModelId;
    }


    public double nextInterval(Card card, int ease) {
        return mDeck.nextInterval(card, ease);
    }

    // Leech flag
    public boolean getLeechFlag() {
        return isLeechMarked;
    }
    public void setLeechFlag(boolean flag) {
        isLeechMarked = flag;
    }
    // Suspended flag
    public boolean getSuspendedFlag() {
        return isLeechSuspended;
    }
    public void setSuspendedFlag(boolean flag) {
        isLeechSuspended = flag;
    }

    public int getSuccessive() {
        return mSuccessive;
    }
}
