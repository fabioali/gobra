// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobra.frontend.info.implementation.property

import viper.gobra.ast.frontend._
import viper.gobra.frontend.info.base.SymbolTable.SingleConstant
import viper.gobra.frontend.info.base.Type.{BooleanT, IntT}
import viper.gobra.frontend.info.implementation.TypeInfoImpl
import viper.gobra.util.TypeBounds.{DefaultInt, DefaultUInt, IntWith64Bit, Signed, SignedInteger16, SignedInteger32, SignedInteger64, SignedInteger8, UIntWith64Bit, UnboundedInteger, Unsigned, UnsignedInteger16, UnsignedInteger32, UnsignedInteger64, UnsignedInteger8}

trait ConstantEvaluation { this: TypeInfoImpl =>

  lazy val boolConstantEval: PExpression => Option[Boolean] =
    attr[PExpression, Option[Boolean]] {
      case PBoolLit(lit) => Some(lit)
      case e: PUnaryExp => e match {
        case _: PNegation => boolConstantEval(e.operand).map(!_)
        case _ => None
      }
      case e: PBinaryExp[_,_] =>
        def auxBool[T](l: PExpression, r: PExpression)(f: Boolean => Boolean => Boolean): Option[Boolean] =
          (boolConstantEval(l), boolConstantEval(r)) match {
            case (Some(a), Some(b)) => Some(f(a)(b))
            case _ => None
          }
        def auxInt[T](l: PExpression, r: PExpression)(f: BigInt => BigInt => Boolean): Option[Boolean] =
          (intConstantEval(l), intConstantEval(r)) match {
            case (Some(a), Some(b)) => Some(f(a)(b))
            case _ => None
          }

        for {
          l <- asExpr(e.left)
          r <- asExpr(e.right)
          res <- e match {
            case _: PEquals if typ(l) == BooleanT => auxBool(l,r)(x => y => x == y)
            case _: PEquals if typ(l).isInstanceOf[IntT] => auxInt(l,r)(x => y => x == y)
            case _: PUnequals if typ(l) == BooleanT => auxBool(l,r)(x => y => x != y)
            case _: PUnequals if typ(l).isInstanceOf[IntT] => auxInt(l,r)(x => y => x != y)
            case _: PAnd if typ(l) == BooleanT => auxBool(l,r)(x => y => x && y)
            case _: POr if typ(l) == BooleanT => auxBool(l,r)(x => y => x || y)
            case _: PLess if typ(l).isInstanceOf[IntT] => auxInt(l,r)(x => y => x < y)
            case _: PAtMost if typ(l).isInstanceOf[IntT] => auxInt(l,r)(x => y => x <= y)
            case _: PGreater if typ(l).isInstanceOf[IntT] => auxInt(l,r)(x => y => x > y)
            case _: PAtLeast if typ(l).isInstanceOf[IntT] => auxInt(l,r)(x => y => x >= y)
            case _ => None
          }
        } yield res

      case PNamedOperand(id) => entity(id) match {
        case SingleConstant(_, _, exp, _, _, context) => context.boolConstantEvaluation(exp)
        case _ => None
      }
      case PDot(_, id) => entity(id) match {
        case SingleConstant(_, _, exp, _, _, _) => boolConstantEval(exp)
        case _ => None
      }

      case _ => None
    }

  lazy val intConstantEval: PExpression => Option[BigInt] =
    attr[PExpression, Option[BigInt]] {
      case PIntLit(lit, _) => Some(lit)
      case e: PBitwiseNegation => ???
      case e: PBinaryExp[_,_] =>
        def aux(l: PExpression, r: PExpression)(f: BigInt => BigInt => BigInt): Option[BigInt] =
          (intConstantEval(l), intConstantEval(r)) match {
            case (Some(a), Some(b)) => Some(f(a)(b))
            case _ => None
          }

        for {
          l <- asExpr(e.left)
          r <- asExpr(e.right)
          res <- e match {
            case _: PAdd => aux(l, r)(x => y => x + y)
            case _: PSub => aux(l, r)(x => y => x - y)
            case _: PMul => aux(l, r)(x => y => x * y)
            case _: PMod => aux(l, r)(x => y => x % y)
            case _: PDiv => aux(l, r)(x => y => x / y)
            case _: PShiftLeft =>
              for {
                constL <- intConstantEval(l)
                constR <- intConstantEval(r)
                v = underlyingType(typ(l)) match {
                  case IntT(t: Signed) => t match {
                    case SignedInteger8 =>
                      constL.byteValue << constR.intValue
                    case SignedInteger16 =>
                      constL.shortValue << constR.intValue
                    case SignedInteger32 | DefaultInt =>
                      constL.intValue << constR.intValue
                    case SignedInteger64 | IntWith64Bit =>
                      constL.longValue << constR.intValue
                  }
                  case IntT(t: Unsigned) => t match {
                    case UnsignedInteger8 =>
                      constL.byteValue << constR.intValue
                    case UnsignedInteger16 =>
                      constL.shortValue << constR.intValue
                    case UnsignedInteger32 | DefaultUInt =>
                      constL.intValue << constR.intValue
                    case UnsignedInteger64 | UIntWith64Bit =>
                      constL.longValue << constR.intValue
                  }
                  case IntT(UnboundedInteger) => ??? // should be violation
                    /*
                    if (config.int32bit) {
                      constL.intValue << constR.longValue
                    } else {
                      constL.longValue << constR.longValue
                    }
                     */
                }
              } yield BigInt(v)

            case _: PShiftRight =>
              for {
                constL <- intConstantEval(l)
                constR <- intConstantEval(r)
                v = underlyingType(typ(l)) match {
                  case IntT(t: Signed) => t match {
                    case SignedInteger8 =>
                      constL.byteValue >> constR.intValue
                    case SignedInteger16 =>
                      constL.shortValue >> constR.intValue
                    case SignedInteger32 | DefaultInt =>
                      constL.intValue >> constR.intValue
                    case SignedInteger64 | IntWith64Bit =>
                      constL.longValue >> constR.intValue
                  }
                  case IntT(t: Unsigned) => t match {
                    case UnsignedInteger8 =>
                      constL.byteValue >>> constR.intValue
                    case UnsignedInteger16 =>
                      constL.shortValue >>> constR.intValue
                    case UnsignedInteger32 | DefaultUInt =>
                      constL.intValue >>> constR.intValue
                    case UnsignedInteger64 | UIntWith64Bit =>
                      constL.longValue >>> constR.intValue
                  }
                  case _ => ???
                    /*
                    if (config.int32bit) {
                      constL.intValue >> constR.longValue
                    } else {
                      constL.longValue >> constR.longValue
                    }
                     */
                }
              } yield BigInt(v)
            case _: PBitwiseAnd => ???
            case _: PBitwiseOr => ???
            case _: PBitwiseXor => ???
            case _: PBitClear => ???

            case _ => None
          }
        } yield res

      case PNamedOperand(id) => entity(id) match {
        case SingleConstant(_, _, exp, _, _, context) => context.intConstantEvaluation(exp)
        case _ => None
      }
      case PDot(_, id) => entity(id) match {
        case SingleConstant(_, _, exp, _, _, _) => intConstantEval(exp)
        case _ => None
      }

      case _ => None
    }

  lazy val stringConstantEval: PExpression => Option[String] = {
    attr[PExpression, Option[String]] {
      case PStringLit(lit) => Some(lit)

      case PAdd(l,r) => for {
        lStr <- stringConstantEval(l)
        rStr <- stringConstantEval(r)
      } yield lStr + rStr

      case _ => None
    }
  }
}
