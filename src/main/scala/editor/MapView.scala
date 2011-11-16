package rpgboss.editor

import rpgboss.lib._
import rpgboss.cache._
import rpgboss.model._
import rpgboss.message._

import rpgboss.editor.tileset._
import rpgboss.editor.lib._
import rpgboss.editor.lib.GraphicsUtils._

import scala.math._
import scala.swing._
import scala.swing.event._

import java.awt.{Dimension, Rectangle}

class MapView(sm: StateMaster, tileSelector: TabbedTileSelector)
extends BoxPanel(Orientation.Vertical) with SelectsMap
{
  //--- VARIABLES ---//
  var viewStateOpt : Option[MapViewState] = None
  var curTilesize = Tileset.tilesize
  
  //--- BUTTONS ---//
  def scaleButton(title: String, invScale: Int) = new RadioButton() { 
    action = Action(title) {
      curTilesize = Tileset.tilesize / invScale
      resizeRevalidateRepaint()
    }
  }
  
  val s11Btn = scaleButton("1/1", 1)
  val s12Btn = scaleButton("1/2", 2)
  val s14Btn = scaleButton("1/4", 4)
  
  //--- WIDGETS --//
  val toolbar = new BoxPanel(Orientation.Horizontal) {
    minimumSize = new Dimension(200, 32)
    def addBtnsAsGrp(btns: List[RadioButton]) = {
      val grp = new ButtonGroup(btns : _*)
      grp.select(btns.head)
      
      contents ++= btns
    }
    
    def enumButtons[T](enum: ListedEnum[T]) = 
      enum.valueList.map { eVal =>
        new RadioButton() {
          action = Action(eVal.toString) { enum.selected = eVal }
        }
      }
    
    addBtnsAsGrp(enumButtons(MapLayers))
    addBtnsAsGrp(enumButtons(MapViewTools))
    addBtnsAsGrp(List(s11Btn, s12Btn, s14Btn))
    
    contents += Swing.HGlue
  }
  
  val canvasPanel = new Panel() {
    var cursorSquare : TileRect = TileRect()
    
    override def paintComponent(g: Graphics2D) =
    {
      super.paintComponent(g)
      
      viewStateOpt.map(vs => {
        def byteIdx(xTile: Int, yTile: Int) = 
          (xTile + (yTile*vs.mapMeta.xSize))*RpgMap.bytesPerTile
        
        val bounds = g.getClipBounds
        val tilesize = curTilesize
        
        val minXTile = (bounds.getMinX/tilesize).toInt
        val minYTile = (bounds.getMinY/tilesize).toInt
        
        val maxXTile = 
          min(vs.mapMeta.xSize-1, (bounds.getMaxX.toInt-1)/tilesize)
        val maxYTile = 
          min(vs.mapMeta.ySize-1, (bounds.getMaxY.toInt-1)/tilesize)
        
        g.clip(new Rectangle(minXTile*tilesize, minYTile*tilesize,
                             (maxXTile-minXTile+1)*tilesize,
                             (maxYTile-minYTile+1)*tilesize))
        
        println("Paint Tiles: x: [%d,%d], y: [%d,%d]".format(
          minXTile, maxXTile, minYTile, maxYTile))
        
        // draw tiles
        vs.nextMapData.drawOrder.map(layer => {
          for(xTile <- minXTile to maxXTile; yTile <- minYTile to maxYTile) {
            val bi = byteIdx(xTile, yTile)
            if(layer(bi) != -1) {
              val tileImg = vs.tilecache.getTileImage(layer, bi, 0)
              
              g.drawImage(tileImg, 
                          xTile*tilesize, yTile*tilesize,
                          (xTile+1)*tilesize, (yTile+1)*tilesize,
                          0, 0, Tileset.tilesize, Tileset.tilesize,
                          null)
            }
          }   
        })
        
        // draw selection square
        cursorSquare.optionallyDrawSelRect(g, curTilesize, curTilesize)
      })
    }
  }
  
  //--- ADDING WIDGETS ---//
  contents += toolbar
  contents += new ScrollPane(canvasPanel) {
    horizontalScrollBarPolicy = ScrollPane.BarPolicy.Always
    verticalScrollBarPolicy = ScrollPane.BarPolicy.Always
  }
  
  //--- MISC FUNCTIONS ---//
  def toTileCoords(p: Point) = 
    (p.getX.toInt/curTilesize, p.getY.toInt/curTilesize) 
  
  def resizeRevalidateRepaint() = {
    canvasPanel.preferredSize = viewStateOpt.map { vs =>
      new Dimension(vs.mapMeta.xSize*curTilesize,
                    vs.mapMeta.ySize*curTilesize)
    } getOrElse {
      new Dimension(0,0)
    }
    
    canvasPanel.revalidate()
    canvasPanel.repaint()
  }
  
  def selectMap(mapOpt: Option[RpgMap]) = {
    viewStateOpt = mapOpt map { mapMeta =>
      val tc = new TileCache(sm.proj, sm.autotiles, mapMeta)
      new MapViewState(sm, mapMeta.id, tc)
    }
      
    resizeRevalidateRepaint()
  }
  
  // Returns TileRect to redraw
  def updateCursorSq(visible: Boolean, x: Int = 0, y: Int = 0) : TileRect = 
  {
    val oldSq = canvasPanel.cursorSquare
    val newSq = if(visible) {
      val tCodes = tileSelector.selectedTileCodes 
      assert(tCodes.length > 0 && tCodes(0).length > 0, "Selected tiles empty")
      TileRect(x, y, tCodes(0).length, tCodes.length)
    } else TileRect()
    
    // if updated, redraw. otherwise, don't redraw
    if(oldSq != newSq) {
      canvasPanel.cursorSquare = newSq
      oldSq|newSq
    } else TileRect()
  }
  
  //--- REACTIONS ---//
  listenTo(canvasPanel.mouse.clicks, canvasPanel.mouse.moves)
  
  def repaintRegions(r1: TileRect, r2: TileRect = TileRect()) =
    canvasPanel.repaint((r1|r2).rect(curTilesize, curTilesize))
  
  reactions += {
    case MouseMoved(`canvasPanel`, p, _) => {
      val (tileX, tileY) = toTileCoords(p)
      repaintRegions(updateCursorSq(true, tileX, tileY))
    }
    case MouseExited(`canvasPanel`, _, _) =>
      repaintRegions(updateCursorSq(false))
    case MousePressed(`canvasPanel`, point, _, _, _) => {
      viewStateOpt map { vs => 
        val tCodes = tileSelector.selectedTileCodes
        val tool = MapViewTools.selected
        val (x1, y1) = toTileCoords(point)
                
        repaintRegions(
          updateCursorSq(tool.selectionSqOnDrag, x1, y1),
          MapViewTools.selected.onMousePressed(vs, tCodes, x1, y1))
        
        lazy val temporaryReactions : PartialFunction[Event, Unit] = { 
          case MouseDragged(`canvasPanel`, point, _) => {
            val (x2, y2) = toTileCoords(point)
            
            repaintRegions(
              updateCursorSq(tool.selectionSqOnDrag, x2, y2),
              MapViewTools.selected.onMouseDragged(vs, tCodes, x1, y1, x2, y2))
          }
          case MouseReleased(`canvasPanel`, point, _, _, _) => {
            val (x2, y2) = toTileCoords(point)
            vs.commitNextData()
            
            repaintRegions(updateCursorSq(true, x2, y2))
            
            reactions -= temporaryReactions
          }
        }
        
        reactions += temporaryReactions
      }
    }
  }
}
