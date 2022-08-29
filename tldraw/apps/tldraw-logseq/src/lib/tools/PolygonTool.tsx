import { TLBoxTool } from '@tldraw/core'
import type { TLReactEventMap } from '@tldraw/react'
import { PolygonShape, type Shape } from '../shapes'

export class PolygonTool extends TLBoxTool<PolygonShape, Shape, TLReactEventMap> {
  static id = 'polygon'
  static shortcut = ['g']
  Shape = PolygonShape
}
