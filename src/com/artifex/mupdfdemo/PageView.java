package com.artifex.mupdfdemo;

import java.util.ArrayList;
import java.util.Iterator;

import com.test.dvb.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

class PatchInfo {
	public BitmapHolder bmh;
	public Bitmap bm;
	public Point patchViewSize;
	public Rect  patchArea;
	public boolean completeRedraw;

	public PatchInfo(Point aPatchViewSize, Rect aPatchArea, BitmapHolder aBmh, boolean aCompleteRedraw) {
		bmh = aBmh;
		bm = null;
		patchViewSize = aPatchViewSize;
		patchArea = aPatchArea;
		completeRedraw = aCompleteRedraw;
	}
}

// Make our ImageViews opaque to optimize redraw
class OpaqueImageView extends ImageView {

	public OpaqueImageView(Context context) {
		super(context);
	}

	@Override
	public boolean isOpaque() {
		return true;
	}
}

abstract class TextSelector {
	final private TextWord[][] mText;
	final private RectF mSelectBox;

	public TextSelector(TextWord[][] text, RectF selectBox) {
		mText = text;
		mSelectBox = selectBox;
	}

	protected abstract void onStartLine();
	protected abstract void onWord(TextWord word);
	protected abstract void onEndLine();

	public void select() {
		if (mText == null || mSelectBox == null)
			return;

		ArrayList<TextWord[]> lines = new ArrayList<TextWord[]>();
		for (TextWord[] line : mText)
			if (line[0].bottom > mSelectBox.top && line[0].top < mSelectBox.bottom)
				lines.add(line);

		Iterator<TextWord[]> it = lines.iterator();
		while (it.hasNext()) {
			TextWord[] line = it.next();
			boolean firstLine = line[0].top < mSelectBox.top;
			boolean lastLine = line[0].bottom > mSelectBox.bottom;
			float start = Float.NEGATIVE_INFINITY;
			float end = Float.POSITIVE_INFINITY;

			if (firstLine && lastLine) {
				start = Math.min(mSelectBox.left, mSelectBox.right);
				end = Math.max(mSelectBox.left, mSelectBox.right);
			} else if (firstLine) {
				start = mSelectBox.left;
			} else if (lastLine) {
				end = mSelectBox.right;
			}

			onStartLine();

			for (TextWord word : line)
				if (word.right > start && word.left < end)
					onWord(word);

			onEndLine();
		}
	}
}

public abstract class PageView extends ViewGroup {
	private static final int HIGHLIGHT_COLOR = 0x802572AC;
	private static final int LINK_COLOR = 0x80AC7225;
	private static final int BACKGROUND_COLOR = 0xFFFFFFFF;
	private static final int PROGRESS_DIALOG_DELAY = 200;
	private static final float LINE_THICKNESS = 0.07f;
	private static final float STRIKE_HEIGHT = 0.375f;
	private final Context   mContext;
	protected     int       mPageNumber;
	private       Point     mParentSize;
	protected     Point     mSize;   // Size of page at minimum zoom
	protected     float     mSourceScale;

	private       ImageView mEntire; // Image rendered at minimum zoom
	private       BitmapHolder mEntireBmh;
	private       AsyncTask<Void,Void,TextWord[][]> mGetText;
	private       AsyncTask<RectF[],Void,Void> mAddStrikeOut;
	private       AsyncTask<Void,Void,LinkInfo[]> mGetLinkInfo;
	private       AsyncTask<Void,Void,Bitmap> mDrawEntire;

	private       Point     mPatchViewSize; // View size on the basis of which the patch was created
	private       Rect      mPatchArea;
	private       ImageView mPatch;
	private       BitmapHolder mPatchBmh;
	private       AsyncTask<PatchInfo,Void,PatchInfo> mDrawPatch;
	private       RectF     mSearchBoxes[];
	protected     LinkInfo  mLinks[];
	private       RectF     mSelectBox;
	private       TextWord  mText[][];
	private       View      mSearchView;
	private       boolean   mIsBlank;
	private       boolean   mHighlightLinks;

	private       ProgressBar mBusyIndicator;
	private final Handler   mHandler = new Handler();

	public PageView(Context c, Point parentSize) {
		super(c);
		mContext    = c;
		mParentSize = parentSize;
		setBackgroundColor(BACKGROUND_COLOR);
		mEntireBmh = new BitmapHolder();
		mPatchBmh = new BitmapHolder();
	}

	protected abstract Bitmap drawPage(int sizeX, int sizeY, int patchX, int patchY, int patchWidth, int patchHeight);
	protected abstract Bitmap updatePage(BitmapHolder h, int sizeX, int sizeY, int patchX, int patchY, int patchWidth, int patchHeight);
	protected abstract LinkInfo[] getLinkInfo();
	protected abstract TextWord[][] getText();
	protected abstract void addStrikeOut(RectF[] lines);

	private void reinit() {
		// Cancel pending render task
		if (mDrawEntire != null) {
			mDrawEntire.cancel(true);
			mDrawEntire = null;
		}

		if (mDrawPatch != null) {
			mDrawPatch.cancel(true);
			mDrawPatch = null;
		}

		if (mGetLinkInfo != null) {
			mGetLinkInfo.cancel(true);
			mGetLinkInfo = null;
		}

		if (mGetText != null) {
			mGetText.cancel(true);
			mGetText = null;
		}

		mIsBlank = true;
		mPageNumber = 0;

		if (mSize == null)
			mSize = mParentSize;

		if (mEntire != null) {
			mEntire.setImageBitmap(null);
			mEntireBmh.setBm(null);
		}

		if (mPatch != null) {
			mPatch.setImageBitmap(null);
			mPatchBmh.setBm(null);
		}

		mPatchViewSize = null;
		mPatchArea = null;

		mSearchBoxes = null;
		mLinks = null;
		mSelectBox = null;
		mText = null;
	}

	public void releaseResources() {
		reinit();

		if (mBusyIndicator != null) {
			removeView(mBusyIndicator);
			mBusyIndicator = null;
		}
	}

	public void blank(int page) {
		reinit();
		mPageNumber = page;

		if (mBusyIndicator == null) {
			mBusyIndicator = new ProgressBar(mContext);
			mBusyIndicator.setIndeterminate(true);
			mBusyIndicator.setBackgroundResource(R.drawable.busy);
			addView(mBusyIndicator);
		}
	}

	public void setPage(int page, PointF size) {
		// Cancel pending render task
		if (mDrawEntire != null) {
			mDrawEntire.cancel(true);
			mDrawEntire = null;
		}

		mIsBlank = false;

		mPageNumber = page;
		if (mEntire == null) {
			mEntire = new OpaqueImageView(mContext);
			mEntire.setScaleType(ImageView.ScaleType.FIT_CENTER);
			addView(mEntire);
		}

		// Calculate scaled size that fits within the screen limits
		// This is the size at minimum zoom
		mSourceScale = Math.min(mParentSize.x/size.x, mParentSize.y/size.y);
		Point newSize = new Point((int)(size.x*mSourceScale), (int)(size.y*mSourceScale));
		mSize = newSize;

		mEntire.setImageBitmap(null);
		mEntireBmh.setBm(null);

		// Get the link info in the background
		mGetLinkInfo = new AsyncTask<Void,Void,LinkInfo[]>() {
			protected LinkInfo[] doInBackground(Void... v) {
				return getLinkInfo();
			}

			protected void onPostExecute(LinkInfo[] v) {
				mLinks = v;
				invalidate();
			}
		};

		mGetLinkInfo.execute();

		// Render the page in the background
		mDrawEntire = new AsyncTask<Void,Void,Bitmap>() {
			protected Bitmap doInBackground(Void... v) {
				return drawPage(mSize.x, mSize.y, 0, 0, mSize.x, mSize.y);
			}

			protected void onPreExecute() {
				mEntire.setImageBitmap(null);
				mEntireBmh.setBm(null);

				if (mBusyIndicator == null) {
					mBusyIndicator = new ProgressBar(mContext);
					mBusyIndicator.setIndeterminate(true);
					mBusyIndicator.setBackgroundResource(R.drawable.busy);
					addView(mBusyIndicator);
					mBusyIndicator.setVisibility(INVISIBLE);
					mHandler.postDelayed(new Runnable() {
						public void run() {
							if (mBusyIndicator != null)
								mBusyIndicator.setVisibility(VISIBLE);
						}
					}, PROGRESS_DIALOG_DELAY);
				}
			}

			protected void onPostExecute(Bitmap bm) {
				removeView(mBusyIndicator);
				mBusyIndicator = null;
				mEntire.setImageBitmap(bm);
				mEntireBmh.setBm(bm);
				invalidate();
			}
		};

		mDrawEntire.execute();

		if (mSearchView == null) {
			mSearchView = new View(mContext) {
				@Override
				protected void onDraw(final Canvas canvas) {
					super.onDraw(canvas);
					// Work out current total scale factor
					// from source to view
					final float scale = mSourceScale*(float)getWidth()/(float)mSize.x;
					final Paint paint = new Paint();

					if (!mIsBlank && mSearchBoxes != null) {
						paint.setColor(HIGHLIGHT_COLOR);
						for (RectF rect : mSearchBoxes)
							canvas.drawRect(rect.left*scale, rect.top*scale,
									        rect.right*scale, rect.bottom*scale,
									        paint);
					}

					if (!mIsBlank && mLinks != null && mHighlightLinks) {
						paint.setColor(LINK_COLOR);
						for (LinkInfo link : mLinks)
							canvas.drawRect(link.rect.left*scale, link.rect.top*scale,
									        link.rect.right*scale, link.rect.bottom*scale,
									        paint);
					}

					if (mSelectBox != null && mText != null) {
						paint.setColor(HIGHLIGHT_COLOR);
						TextSelector sel = new TextSelector(mText, mSelectBox) {
							RectF rect;

							@Override
							protected void onStartLine() {
								rect = new RectF();
							}

							@Override
							protected void onWord(TextWord word) {
								rect.union(word);
							}

							@Override
							protected void onEndLine() {
								if (!rect.isEmpty())
									canvas.drawRect(rect.left*scale, rect.top*scale, rect.right*scale, rect.bottom*scale, paint);
							}
						};

						sel.select();
					}
				}
			};

			addView(mSearchView);
		}
		requestLayout();
	}

	public void setSearchBoxes(RectF searchBoxes[]) {
		mSearchBoxes = searchBoxes;
		if (mSearchView != null)
			mSearchView.invalidate();
	}

	public void setLinkHighlighting(boolean f) {
		mHighlightLinks = f;
		if (mSearchView != null)
			mSearchView.invalidate();
	}

	public void deselectText() {
		mSelectBox = null;
		mSearchView.invalidate();
	}

	public void selectText(float x0, float y0, float x1, float y1) {
		float scale = mSourceScale*(float)getWidth()/(float)mSize.x;
		float docRelX0 = (x0 - getLeft())/scale;
		float docRelY0 = (y0 - getTop())/scale;
		float docRelX1 = (x1 - getLeft())/scale;
		float docRelY1 = (y1 - getTop())/scale;
		// Order on Y but maintain the point grouping
		if (docRelY0 <= docRelY1)
			mSelectBox = new RectF(docRelX0, docRelY0, docRelX1, docRelY1);
		else
			mSelectBox = new RectF(docRelX1, docRelY1, docRelX0, docRelY0);

		mSearchView.invalidate();

		if (mGetText == null) {
			mGetText = new AsyncTask<Void,Void,TextWord[][]>() {
				@Override
				protected TextWord[][] doInBackground(Void... params) {
					return getText();
				}
				@Override
				protected void onPostExecute(TextWord[][] result) {
					mText = result;
					mSearchView.invalidate();
				}
			};

			mGetText.execute();
		}
	}

	public boolean copySelection() {
		final StringBuilder text = new StringBuilder();

		TextSelector sel = new TextSelector(mText, mSelectBox) {
			StringBuilder line;

			@Override
			protected void onStartLine() {
				line = new StringBuilder();
			}

			@Override
			protected void onWord(TextWord word) {
				if (line.length() > 0)
					line.append(' ');
				line.append(word.w);
			}

			@Override
			protected void onEndLine() {
				if (text.length() > 0)
					text.append('\n');
				text.append(line);
			}
		};

		sel.select();

		if (text.length() == 0)
			return false;

		int currentApiVersion = android.os.Build.VERSION.SDK_INT;
		if (currentApiVersion >= android.os.Build.VERSION_CODES.HONEYCOMB) {
			android.content.ClipboardManager cm = (android.content.ClipboardManager)mContext.getSystemService(Context.CLIPBOARD_SERVICE);

			cm.setPrimaryClip(ClipData.newPlainText("MuPDF", text));
		} else {
			android.text.ClipboardManager cm = (android.text.ClipboardManager)mContext.getSystemService(Context.CLIPBOARD_SERVICE);
			cm.setText(text);
		}

		mSelectBox = null;
		mSearchView.invalidate();

		return true;
	}

	public void strikeOutSelection() {
		final ArrayList<RectF> lines = new ArrayList<RectF>();
		TextSelector sel = new TextSelector(mText, mSelectBox) {
			RectF rect;

			@Override
			protected void onStartLine() {
				rect = new RectF();
			}

			@Override
			protected void onWord(TextWord word) {
				rect.union(word);
			}

			@Override
			protected void onEndLine() {
				if (!rect.isEmpty()) {
					// These are vertical lines so we can specify
					// both position and thickness with a RectF
					float vcenter = rect.bottom - (rect.bottom - rect.top)*STRIKE_HEIGHT;
					float thickness = (rect.bottom - rect.top)*LINE_THICKNESS;
					rect.top = vcenter - thickness/2;
					rect.bottom = vcenter + thickness/2;
					lines.add(rect);
				}
			}
		};

		sel.select();

		mAddStrikeOut = new AsyncTask<RectF[],Void,Void>() {
			@Override
			protected Void doInBackground(RectF[]... params) {
				addStrikeOut(params[0]);
				return null;
			}

			@Override
			protected void onPostExecute(Void result) {
				update();
			}
		};

		mAddStrikeOut.execute(lines.toArray(new RectF[lines.size()]));

		mSelectBox = null;
		mSearchView.invalidate();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int x, y;
		switch(View.MeasureSpec.getMode(widthMeasureSpec)) {
		case View.MeasureSpec.UNSPECIFIED:
			x = mSize.x;
			break;
		default:
			x = View.MeasureSpec.getSize(widthMeasureSpec);
		}
		switch(View.MeasureSpec.getMode(heightMeasureSpec)) {
		case View.MeasureSpec.UNSPECIFIED:
			y = mSize.y;
			break;
		default:
			y = View.MeasureSpec.getSize(heightMeasureSpec);
		}

		setMeasuredDimension(x, y);

		if (mBusyIndicator != null) {
			int limit = Math.min(mParentSize.x, mParentSize.y)/2;
			mBusyIndicator.measure(View.MeasureSpec.AT_MOST | limit, View.MeasureSpec.AT_MOST | limit);
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		int w  = right-left;
		int h = bottom-top;

		if (mEntire != null) {
			mEntire.layout(0, 0, w, h);
		}

		if (mSearchView != null) {
			mSearchView.layout(0, 0, w, h);
		}

		if (mPatchViewSize != null) {
			if (mPatchViewSize.x != w || mPatchViewSize.y != h) {
				// Zoomed since patch was created
				mPatchViewSize = null;
				mPatchArea     = null;
				if (mPatch != null) {
					mPatch.setImageBitmap(null);
					mPatchBmh.setBm(null);
				}
			} else {
				mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
			}
		}

		if (mBusyIndicator != null) {
			int bw = mBusyIndicator.getMeasuredWidth();
			int bh = mBusyIndicator.getMeasuredHeight();

			mBusyIndicator.layout((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2);
		}
	}

	public void addHq(boolean update) {
		Rect viewArea = new Rect(getLeft(),getTop(),getRight(),getBottom());
		// If the viewArea's size matches the unzoomed size, there is no need for an hq patch
		if (viewArea.width() != mSize.x || viewArea.height() != mSize.y) {
			Point patchViewSize = new Point(viewArea.width(), viewArea.height());
			Rect patchArea = new Rect(0, 0, mParentSize.x, mParentSize.y);

			// Intersect and test that there is an intersection
			if (!patchArea.intersect(viewArea))
				return;

			// Offset patch area to be relative to the view top left
			patchArea.offset(-viewArea.left, -viewArea.top);

			boolean area_unchanged = patchArea.equals(mPatchArea) && patchViewSize.equals(mPatchViewSize);

			// If being asked for the same area as last time and not because of an update then nothing to do
			if (area_unchanged && !update)
				return;

			boolean completeRedraw = !(area_unchanged && update);

			// Stop the drawing of previous patch if still going
			if (mDrawPatch != null) {
				mDrawPatch.cancel(true);
				mDrawPatch = null;
			}

			if (completeRedraw) {
				// The bitmap holder mPatchBm may still be rendered to by a
				// previously invoked task, and possibly for a different
				// area, so we cannot risk the bitmap generated by this task
				// being passed to it
				mPatchBmh.drop();
				mPatchBmh = new BitmapHolder();
			}

			// Create and add the image view if not already done
			if (mPatch == null) {
				mPatch = new OpaqueImageView(mContext);
				mPatch.setScaleType(ImageView.ScaleType.FIT_CENTER);
				addView(mPatch);
				mSearchView.bringToFront();
			}

			mDrawPatch = new AsyncTask<PatchInfo,Void,PatchInfo>() {
				protected PatchInfo doInBackground(PatchInfo... v) {
					if (v[0].completeRedraw) {
						v[0].bm = drawPage(v[0].patchViewSize.x, v[0].patchViewSize.y,
									v[0].patchArea.left, v[0].patchArea.top,
									v[0].patchArea.width(), v[0].patchArea.height());
					} else {
						v[0].bm = updatePage(v[0].bmh, v[0].patchViewSize.x, v[0].patchViewSize.y,
									v[0].patchArea.left, v[0].patchArea.top,
									v[0].patchArea.width(), v[0].patchArea.height());
					}

					return v[0];
				}

				protected void onPostExecute(PatchInfo v) {
					if (mPatchBmh == v.bmh) {
						mPatchViewSize = v.patchViewSize;
						mPatchArea     = v.patchArea;
						if (v.bm != null) {
							mPatch.setImageBitmap(v.bm);
							v.bmh.setBm(v.bm);
							v.bm = null;
						}
						//requestLayout();
						// Calling requestLayout here doesn't lead to a later call to layout. No idea
						// why, but apparently others have run into the problem.
						mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
						invalidate();
					}
				}
			};

			mDrawPatch.execute(new PatchInfo(patchViewSize, patchArea, mPatchBmh, completeRedraw));
		}
	}

	public void update() {
		// Cancel pending render task
		if (mDrawEntire != null) {
			mDrawEntire.cancel(true);
			mDrawEntire = null;
		}

		if (mDrawPatch != null) {
			mDrawPatch.cancel(true);
			mDrawPatch = null;
		}

		// Render the page in the background
		mDrawEntire = new AsyncTask<Void,Void,Bitmap>() {
			protected Bitmap doInBackground(Void... v) {
				// Pass the current bitmap as a basis for the update, but use a bitmap
				// holder so that the held bitmap will be nulled and not hold on to
				// memory, should this view become redundant.
				return updatePage(mEntireBmh, mSize.x, mSize.y, 0, 0, mSize.x, mSize.y);
			}

			protected void onPostExecute(Bitmap bm) {
				if (bm != null) {
					mEntire.setImageBitmap(bm);
					mEntireBmh.setBm(bm);
				}
				invalidate();
			}
		};

		mDrawEntire.execute();

		addHq(true);
	}

	public void removeHq() {
			// Stop the drawing of the patch if still going
			if (mDrawPatch != null) {
				mDrawPatch.cancel(true);
				mDrawPatch = null;
			}

			// And get rid of it
			mPatchViewSize = null;
			mPatchArea = null;
			if (mPatch != null) {
				mPatch.setImageBitmap(null);
				mPatchBmh.setBm(null);
			}
	}

	public int getPage() {
		return mPageNumber;
	}

	@Override
	public boolean isOpaque() {
		return true;
	}
}
